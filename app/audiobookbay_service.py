import asyncio
import html
import logging
import os
import re
import shutil
import urllib.parse

from bs4 import BeautifulSoup
from fastapi import HTTPException
import httpx

logger = logging.getLogger(__name__)

ABB_BASE_URL = "https://audiobookbay.lu"

MAX_RETRIES = 2


def _split_title_author(raw_title: str) -> tuple[str, str | None]:
    """Extract ABB's common ``Book title - Author`` suffix conservatively."""
    parts = [part.strip() for part in raw_title.rsplit(" - ", 1)]
    if len(parts) != 2:
        return raw_title.strip(), None
    title, candidate = parts
    words = candidate.split()
    if not title or not 1 <= len(words) <= 8 or len(candidate) > 80:
        return raw_title.strip(), None
    if any(marker in candidate.lower() for marker in ("audiobook", "book ", "books ", "series")):
        return raw_title.strip(), None
    return title, candidate


def _create_driver():
    """Create a memory-optimized headless Chrome driver for constrained environments (Render, Docker)."""
    # Keep desktop browser dependencies out of the Android APK. They are loaded
    # only when the normal server path actually uses Selenium.
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.chrome.service import Service
    from webdriver_manager.chrome import ChromeDriverManager

    options = Options()
    options.add_argument('--headless=new')
    options.add_argument('--no-sandbox')
    options.add_argument('--disable-dev-shm-usage')
    options.add_argument('--disable-gpu')
    options.add_argument('--disable-extensions')
    options.add_argument('--disable-plugins-discovery')
    options.add_argument('--disable-software-rasterizer')
    options.add_argument('--disable-background-networking')
    options.add_argument('--disable-default-apps')
    options.add_argument('--disable-sync')
    options.add_argument('--disable-translate')
    options.add_argument('--disable-logging')
    options.add_argument('--single-process')
    options.add_argument('--no-zygote')
    options.add_argument('--no-first-run')
    options.add_argument('--window-size=1280,720')
    options.add_argument('--js-flags=--max-old-space-size=256')
    options.add_argument('--blink-settings=imagesEnabled=false')
    options.add_argument('--disable-blink-features=AutomationControlled')
    options.add_argument('user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
    options.add_experimental_option("excludeSwitches", ["enable-automation"])
    options.add_experimental_option('useAutomationExtension', False)

    # Disable images/CSS to speed up loading
    prefs = {
        'profile.managed_default_content_settings.images': 2,
        'profile.managed_default_content_settings.stylesheets': 2,
    }
    options.add_experimental_option('prefs', prefs)

    # Try system-installed Chromium first (Docker/Linux), fall back to webdriver-manager (local dev)
    chromium_path = shutil.which('chromium') or shutil.which('chromium-browser')
    chromedriver_path = shutil.which('chromedriver')

    if chromium_path and chromedriver_path:
        options.binary_location = chromium_path
        service = Service(chromedriver_path)
    else:
        service = Service(ChromeDriverManager().install())

    driver = webdriver.Chrome(service=service, options=options)
    driver.set_page_load_timeout(60)
    return driver


def _fetch_page_with_retry(url: str, wait_selector: str | None = None) -> str:
    """Fetch a page with Selenium, retrying on timeout. Returns page source HTML."""
    from selenium.webdriver.common.by import By
    from selenium.webdriver.support.ui import WebDriverWait
    from selenium.webdriver.support import expected_conditions as EC

    last_error = None
    for attempt in range(1, MAX_RETRIES + 1):
        driver = None
        try:
            logger.info(f"Selenium fetch attempt {attempt}/{MAX_RETRIES}: {url}")
            driver = _create_driver()
            driver.get(url)

            # Wait for actual content instead of fixed sleep
            if wait_selector:
                try:
                    WebDriverWait(driver, 15).until(
                        EC.presence_of_element_located((By.CSS_SELECTOR, wait_selector))
                    )
                except Exception:
                    # Content might still be there, just not matching selector — continue
                    pass

            return driver.page_source
        except Exception as e:
            last_error = e
            logger.warning(f"Selenium attempt {attempt} failed: {e}")
        finally:
            if driver:
                try:
                    driver.quit()
                except Exception:
                    pass

    raise last_error


def _fetch_page_http(url: str) -> str:
    """Android-compatible fallback where desktop ChromeDriver is unavailable."""
    headers = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        ),
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Upgrade-Insecure-Requests": "1",
    }
    with httpx.Client(headers=headers, follow_redirects=True, timeout=45.0) as client:
        # Establish any cookies which AudiobookBay sets on its home page first.
        if url != ABB_BASE_URL:
            home_response = client.get(ABB_BASE_URL)
            home_response.raise_for_status()
        response = client.get(url, headers={"Referer": f"{ABB_BASE_URL}/"})
        response.raise_for_status()
        return response.text


def _android_search_urls(query: str, page: int) -> list[str]:
    """Return ABB search routes in reliability order for the embedded client."""
    encoded = urllib.parse.quote_plus(query)
    paged_url = f"{ABB_BASE_URL}/page/{max(1, page)}/?s={encoded}"
    if page > 1:
        return [paged_url]
    # ABB sometimes treats a fresh request to /?s= as its homepage. The
    # explicit page/1 route is used first, while the canonical route remains a
    # fallback for installations where WordPress redirects page 1.
    return [paged_url, f"{ABB_BASE_URL}/?s={encoded}"]


def _is_search_results_page(html_content: str, query: str) -> bool:
    """Distinguish a real ABB search page from its unrelated homepage."""
    heading_match = re.search(r"<h1\b[^>]*>(.*?)</h1>", html_content, re.I | re.S)
    if not heading_match:
        return False
    heading_text = html.unescape(re.sub(r"<[^>]+>", " ", heading_match.group(1)))
    heading_tokens = set(re.findall(r"[a-z0-9]+", heading_text.lower()))
    query_tokens = set(re.findall(r"[a-z0-9]+", query.lower()))
    return bool(query_tokens) and query_tokens.issubset(heading_tokens)


def extract_slug_from_url(url: str):
    """
    Extract the audiobook slug from a full AudiobookBay URL.
    e.g. 'https://audiobookbay.lu/audio-books/it-a-novel-4/' -> 'audio-books/it-a-novel-4'
    """
    from urllib.parse import urlparse
    parsed = urlparse(url)
    path = parsed.path.strip('/')
    return path if path else None


def is_audiobookbay_url(text: str) -> bool:
    """Check if a string is an AudiobookBay URL."""
    return bool(re.match(r'https?://(www\.)?audiobookbay\.[a-z]+/', text))


async def search_audiobooks(query: str, page: int = 1):
    """
    Search AudiobookBay for audiobooks.
    Uses Selenium form submission (ABB's ?s= URL param is unreliable and often
    just shows the homepage). For page 1, we navigate to the homepage, type into
    the search box, and submit the form. For subsequent pages, we use URL-based
    pagination on the search results.
    """
    loop = asyncio.get_event_loop()

    if os.environ.get("ANDROID_EMBEDDED") == "1":
        last_error = None
        for search_url in _android_search_urls(query, page):
            try:
                html_content = await loop.run_in_executor(None, _fetch_page_http, search_url)
            except Exception as e:
                last_error = e
                continue

            if not _is_search_results_page(html_content, query):
                continue

            return _rank_search_results(
                _parse_search_results(html_content),
                query,
                retain_unmatched=True,
            )

        detail = f" Details: {last_error}" if last_error else ""
        raise HTTPException(
            status_code=502,
            detail=(
                "AudiobookBay returned its homepage instead of search results. "
                "Please retry, or paste a direct AudiobookBay book URL."
                f"{detail}"
            ),
        )

    def do_search(search_query: str, target_page: int):
        from selenium.webdriver.common.by import By
        from selenium.webdriver.support.ui import WebDriverWait
        from selenium.webdriver.support import expected_conditions as EC

        # ABB ignores the ?s= URL param entirely, so we MUST use form submission
        # for ALL pages. For page > 1, we submit the form first, then navigate
        # to the pagination URL within the same browser session (preserves cookies).
        last_error = None
        for attempt in range(1, MAX_RETRIES + 1):
            driver = None
            try:
                logger.info(f"ABB form search attempt {attempt}/{MAX_RETRIES}: '{search_query}' (page {target_page})")
                driver = _create_driver()
                driver.get(ABB_BASE_URL)

                # Wait for search input to appear
                search_input = WebDriverWait(driver, 15).until(
                    EC.presence_of_element_located((By.CSS_SELECTOR, 'input[name="s"]'))
                )

                # Clear any existing text, type query, and submit
                search_input.clear()
                search_input.send_keys(search_query)
                search_input.send_keys(u'\ue007')  # Press Enter (Keys.RETURN)

                # Wait for search results to load
                WebDriverWait(driver, 15).until(
                    EC.presence_of_element_located((By.CSS_SELECTOR, 'div.post'))
                )

                # If we need page > 1, navigate within the same session
                # (cookies from form submission make the ?s= param work now)
                if target_page > 1:
                    encoded = urllib.parse.quote_plus(search_query)
                    page_url = f"{ABB_BASE_URL}/page/{target_page}/?s={encoded}"
                    logger.info(f"ABB navigating to page {target_page}: {page_url}")
                    driver.get(page_url)

                    # Wait for results on the new page
                    try:
                        WebDriverWait(driver, 15).until(
                            EC.presence_of_element_located((By.CSS_SELECTOR, 'div.post'))
                        )
                    except Exception:
                        pass  # May have fewer/no results on later pages

                logger.info(f"ABB search results loaded for: '{search_query}' (page {target_page})")
                return driver.page_source
            except Exception as e:
                last_error = e
                logger.warning(f"ABB form search attempt {attempt} failed: {e}")
            finally:
                if driver:
                    try:
                        driver.quit()
                    except Exception:
                        pass

        raise last_error or Exception("ABB search failed after all retries")

    try:
        html_content = await loop.run_in_executor(None, do_search, query, page)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Failed to fetch from AudiobookBay via Selenium: {str(e)}")

    return _rank_search_results(_parse_search_results(html_content), query)


def _rank_search_results(
    results: list[dict],
    query: str,
    retain_unmatched: bool = False,
) -> list[dict]:
    """Discard unrelated fallback posts and put the closest title matches first."""
    normalized_query = " ".join(re.findall(r"[a-z0-9]+", query.lower()))
    tokens = [token for token in normalized_query.split() if len(token) > 1]
    if not tokens:
        return results

    ranked = []
    for index, item in enumerate(results):
        title = " ".join(re.findall(r"[a-z0-9]+", item.get("title", "").lower()))
        description = " ".join(
            re.findall(r"[a-z0-9]+", item.get("description", "").lower())
        )
        author = " ".join(re.findall(r"[a-z0-9]+", (item.get("author") or "").lower()))
        title_hits = sum(token in title.split() for token in tokens)
        author_hits = sum(token in author.split() for token in tokens)
        text_hits = sum(token in f"{title} {author} {description}".split() for token in tokens)
        if text_hits == 0 and not retain_unmatched:
            continue
        score = text_hits * 10 + title_hits * 20 + author_hits * 20
        if normalized_query and (normalized_query in title or normalized_query in author):
            score += 100
        if title_hits == len(tokens) or author_hits == len(tokens):
            score += 50
        ranked.append((score, index, item))

    ranked.sort(key=lambda entry: (-entry[0], entry[1]))
    return [item for _, _, item in ranked]


def _parse_search_results(html_content: str):
    soup = BeautifulSoup(html_content, 'html.parser')
    results = []

    # Typical structure: <div class="post"> with h2>a for title and img for cover
    posts = soup.find_all('div', class_='post')

    for post in posts:
        title_div = post.find('div', class_='postTitle')
        title_h2 = title_div.find('h2') if title_div else None
        title_elem = title_h2.find('a') if title_h2 else None

        if not title_elem:
            continue

        raw_title = title_elem.text.strip()
        title, author = _split_title_author(raw_title)
        link = title_elem['href']

        # Extract ID or slug from link (e.g. /audio-books/some-book-name/)
        slug = link.replace(ABB_BASE_URL, '').strip('/')
        if link.startswith('/'):
            slug = link.strip('/')
            link = f"{ABB_BASE_URL}{link}"

        # Get cover image
        img_elem = post.find('img')
        cover_image = img_elem.get('src') if img_elem else None

        # Get details (Category, Language, Size, etc.) inside the postContent
        post_content = post.find('div', class_='postContent')
        desc_text = post_content.text.strip() if post_content else ""

        results.append({
            "id": slug,
            "title": title,
            "author": author,
            "url": link,
            "cover_image": cover_image,
            "description": desc_text[:200] + "..." if len(desc_text) > 200 else desc_text,
            "source": "audiobookbay"
        })

    return results


async def get_audiobook_details(slug: str):
    """
    Fetch details of a specific audiobook and extract the Info Hash to build a magnet link.
    """
    url = f"{ABB_BASE_URL}/{slug}/"
    if not url.endswith('/'):
        url += '/'

    loop = asyncio.get_event_loop()

    def do_fetch(target_url):
        if os.environ.get("ANDROID_EMBEDDED") == "1":
            return _fetch_page_http(target_url)
        return _fetch_page_with_retry(target_url, wait_selector='div.postContent')

    try:
        html_content = await loop.run_in_executor(None, do_fetch, url)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Failed to fetch details from AudiobookBay: {str(e)}")

    soup = BeautifulSoup(html_content, 'html.parser')

    # Title
    title_elem = soup.find('div', class_='postTitle')
    raw_title = title_elem.find('h1').text.strip() if title_elem and title_elem.find('h1') else "Unknown Title"
    title, author = _split_title_author(raw_title)

    # Cover
    cover_elem = soup.find('div', class_='postContent')
    cover_image = None
    if cover_elem:
        img = cover_elem.find('img')
        if img:
            cover_image = img['src']

    # Extract info hash
    # It's usually in a table row: <tr><td class="statusInfo">Info Hash:</td><td>[HASH]</td></tr>
    info_hash = None

    # Look for the tracker table
    tables = soup.find_all('table')
    for table in tables:
        rows = table.find_all('tr')
        for row in rows:
            cols = row.find_all('td')
            if len(cols) == 2 and "Info Hash:" in cols[0].text:
                info_hash = cols[1].text.strip()
                break
        if info_hash:
            break

    if not info_hash:
        raise HTTPException(status_code=404, detail="Info hash not found on the page. Cannot generate magnet link.")

    encoded_title = urllib.parse.quote_plus(title)
    # List of common trackers used by ABB to improve DHT discovery
    trackers = [
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.internetwarriors.net:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce"
    ]
    tracker_suffix = "".join([f"&tr={urllib.parse.quote_plus(tr)}" for tr in trackers])
    magnet_link = f"magnet:?xt=urn:btih:{info_hash}&dn={encoded_title}{tracker_suffix}"

    # Get description text
    desc_div = soup.find('div', class_='desc')
    description = desc_div.text.strip() if desc_div else ""

    return {
        "id": slug,
        "title": title,
        "author": author,
        "cover_image": cover_image,
        "description": description,
        "info_hash": info_hash,
        "magnet_link": magnet_link,
        "source": "audiobook"
    }
