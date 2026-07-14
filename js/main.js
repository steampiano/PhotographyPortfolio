const yearEl = document.getElementById('year');
if (yearEl) yearEl.textContent = new Date().getFullYear();

const gallery = document.getElementById('gallery');

// The two navigable collections, kept up to date as things render. The lightbox
// scrolls through whichever collection a photo was opened from.
let FEATURED = [];
let GRID = [];

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric', month: 'long', day: 'numeric',
  });
}

// On hover/touch-active, a thumbnail expands to its real (uncropped) aspect
// ratio and grows so that BOTH dimensions exceed the square resting size — the
// SHORT axis grows to SHORT_SCALE and the long axis scales up proportionally
// beyond that. So a landscape or portrait photo is larger than its neighbors
// in every direction (better contrast), rather than matching the square's size
// on its short axis like it used to.
const SHORT_SCALE = 1.10;

function applyExpandSize(img) {
  const w = img.naturalWidth, h = img.naturalHeight;
  if (!w || !h) return;
  const ratio = w / h;
  const shortPct = SHORT_SCALE * 100;
  // width% is the long axis for landscapes, the short axis for portraits;
  // the height then derives from width via --expand-ratio (the natural
  // aspect ratio). Percentage `height` is avoided because it doesn't resolve
  // correctly against the containing block for an absolutely positioned
  // <img> in the deploy environment, whereas width% + aspect-ratio does.
  const widthPct = ratio >= 1 ? shortPct * ratio : shortPct;
  img.style.setProperty('--expand-w', widthPct.toFixed(2) + '%');
  img.style.setProperty('--expand-ratio', ratio.toFixed(4));
}

// A handle bubble linking to the matching Instagram profile.
function buildHandleBubble(handle) {
  const bubble = document.createElement('a');
  bubble.className = 'people-bubble';
  bubble.textContent = handle;
  bubble.href = 'https://instagram.com/' + handle.replace(/^@/, '');
  bubble.target = '_blank';
  bubble.rel = 'noopener noreferrer';
  return bubble;
}

function buildPostFigure(post) {
  const figure = document.createElement('figure');
  figure.className = 'post';

  const link = document.createElement('a');
  link.className = 'post-link';
  link.href = 'photo.html?src=' + encodeURIComponent(post.image);
  // Normal click opens the lightbox; ⌘/Ctrl/middle-click still opens the
  // dedicated page in a new tab.
  link.addEventListener('click', (e) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    const idx = GRID.findIndex((p) => p.image === post.image);
    if (idx !== -1) openLightbox(list, idx);
  });

  const img = document.createElement('img');
  img.src = post.thumb || post.image;
  img.alt = post.caption || (post.people ? post.people.join(', ') : '');
  img.loading = 'lazy';
  if (img.complete && img.naturalWidth) {
    applyExpandSize(img);
  } else {
    img.addEventListener('load', () => applyExpandSize(img), { once: true });
  }
  link.appendChild(img);
  figure.appendChild(link);

  appendPostMeta(figure, post);
  return figure;
}

// Event badge + caption + date, shared by both the Recent Work grid and the
// Featured row.
function appendPostMeta(figure, post) {
  if (post.event) {
    const eventEl = document.createElement('span');
    eventEl.className = 'post-event';
    eventEl.textContent = post.event;
    figure.appendChild(eventEl);
  }

  if (post.caption) {
    const caption = document.createElement('figcaption');
    caption.textContent = post.caption;
    figure.appendChild(caption);
  }

  const dateEl = document.createElement('span');
  dateEl.className = 'post-date';
  dateEl.textContent = formatDate(post.date);
  figure.appendChild(dateEl);
}

// A Featured-row item: unlike grid figures, this shows the photo at its true
// aspect ratio (sized by the justified-row layout below), no square crop and
// no hover-expand — there's no crop mismatch here to expand out of.
function buildFeaturedItem(post) {
  const figure = document.createElement('figure');
  figure.className = 'post featured-item';

  const link = document.createElement('a');
  link.className = 'post-link';
  link.href = 'photo.html?src=' + encodeURIComponent(post.image);
  link.addEventListener('click', (e) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    const idx = FEATURED.findIndex((p) => p.image === post.image);
    if (idx !== -1) openLightbox(FEATURED, idx);
  });

  const img = document.createElement('img');
  img.src = post.thumb || post.image;
  img.alt = post.caption || (post.people ? post.people.join(', ') : '');
  link.appendChild(img);
  figure.appendChild(link);

  appendPostMeta(figure, post);
  return figure;
}

// Featured: a "justified" row. Each photo keeps its true aspect ratio; a
// target row height is picked, each photo's natural width at that height is
// summed, and the whole row is scaled up/down so it exactly fills the
// container width — the classic Flickr/Google-Photos justified-gallery
// technique. Rows fill greedily left-to-right, wrapping once a row is full.
function setupFeaturedRow(featuredPosts) {
  const section = document.getElementById('highlights-section');
  const row = document.getElementById('featuredRow');
  if (!section || !row || !featuredPosts.length) return;

  section.hidden = false;
  row.innerHTML = '';

  const GAP = 12;
  const items = featuredPosts.map((post) => {
    const figure = buildFeaturedItem(post);
    row.appendChild(figure);
    return { figure, img: figure.querySelector('img'), ratio: 1 };
  });

  function targetRowHeight(containerWidth) {
    return containerWidth < 560 ? 170 : 260;
  }

  function layout() {
    const containerWidth = row.clientWidth;
    if (!containerWidth) return;
    const rowHeight = targetRowHeight(containerWidth);

    let rowItems = [];
    let rowNaturalWidth = 0;

    function flushRow(isTrailing) {
      if (!rowItems.length) return;
      const totalGap = GAP * (rowItems.length - 1);
      let scale = (containerWidth - totalGap) / rowNaturalWidth;
      // Don't stretch a short trailing row (e.g. the last 1-2 photos left
      // over) to an absurd size just to fill the line.
      if (isTrailing) scale = Math.min(scale, 1.35);
      const h = rowHeight * scale;
      for (const it of rowItems) {
        it.figure.style.width = (h * it.ratio) + 'px';
        it.img.style.height = h + 'px';
      }
      rowItems = [];
      rowNaturalWidth = 0;
    }

    items.forEach((it, i) => {
      rowItems.push(it);
      rowNaturalWidth += rowHeight * it.ratio;
      const totalGap = GAP * (rowItems.length - 1);
      const rowIsFull = rowNaturalWidth + totalGap >= containerWidth;
      const isLastItem = i === items.length - 1;
      if (rowIsFull) flushRow(false);
      else if (isLastItem) flushRow(true);
    });
  }

  // Real aspect ratios are needed before laying out — wait for each thumb to
  // load (they're already being fetched for display, so this adds no extra
  // requests, just a short wait for dimensions).
  Promise.all(items.map((it) => new Promise((resolve) => {
    if (it.img.complete && it.img.naturalWidth) {
      it.ratio = it.img.naturalWidth / it.img.naturalHeight;
      resolve();
    } else {
      it.img.addEventListener('load', () => {
        it.ratio = (it.img.naturalWidth / it.img.naturalHeight) || 1;
        resolve();
      }, { once: true });
      it.img.addEventListener('error', resolve, { once: true });
    }
  }))).then(layout);

  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(layout, 150);
  });
}

// Renders the Recent Work grid, showing only posts whose event is in the
// selected set. An empty set means "no filter" — show everything.
function renderGrid(posts, selectedEvents) {
  gallery.innerHTML = '';
  const visible = selectedEvents.size === 0
    ? posts
    : posts.filter((p) => p.event && selectedEvents.has(p.event));
  GRID = visible;

  if (!visible.length) {
    gallery.innerHTML = '<p class="gallery-empty">No photos match the selected events.</p>';
    return;
  }
  for (const post of visible) {
    gallery.appendChild(buildPostFigure(post));
  }
}

// Builds the "Filter by event" dropdown (checkboxes, OR logic) from the set of
// events present across all posts. Hidden entirely when there are no events.
function setupEventFilter(posts, onChange) {
  const wrap = document.getElementById('eventFilter');
  const btn = document.getElementById('eventFilterBtn');
  const panel = document.getElementById('eventFilterPanel');
  if (!wrap || !btn || !panel) return;

  const events = [...new Set(posts.map((p) => p.event).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b));
  if (!events.length) return;

  wrap.hidden = false;
  const selected = new Set();

  for (const ev of events) {
    const label = document.createElement('label');
    label.className = 'event-option';

    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.value = ev;
    cb.addEventListener('change', () => {
      if (cb.checked) selected.add(ev);
      else selected.delete(ev);
      updateButtonLabel();
      onChange(selected);
    });

    label.appendChild(cb);
    label.appendChild(document.createTextNode(' ' + ev));
    panel.appendChild(label);
  }

  function updateButtonLabel() {
    const caret = '<span class="event-filter-caret">&#9662;</span>';
    btn.innerHTML = (selected.size === 0
      ? 'Filter by event '
      : `Events: ${selected.size} selected `) + caret;
  }

  btn.addEventListener('click', () => {
    const open = panel.hidden;
    panel.hidden = !open;
    btn.setAttribute('aria-expanded', String(open));
  });

  document.addEventListener('click', (e) => {
    if (!wrap.contains(e.target)) {
      panel.hidden = true;
      btn.setAttribute('aria-expanded', 'false');
    }
  });
}

// ---- Lightbox (single photo view) ----
const lightbox = document.getElementById('lightbox');
let lbList = [];
let lbIndex = 0;

// Previews are large (average ~450KB) — fetching+decoding one from scratch on
// every open/step is what made the lightbox feel laggy. Preloading the
// adjacent photos ahead of time means stepping next/prev is usually already
// cached by the time you click. Preload fetches are explicitly marked lower
// priority than the currently-viewed photo's own fetch, so the browser
// doesn't spend bandwidth on a neighbor at the expense of what's on screen.
const preloadedPreviews = new Set();
function preloadPreview(post) {
  if (!post || !post.preview || preloadedPreviews.has(post.preview)) return;
  preloadedPreviews.add(post.preview);
  fetch(post.preview, { priority: 'low' }).catch(() => {});
}

// Fetches a URL with progress reporting (byte-by-byte via a streamed
// response), so the lightbox can show a real download progress bar instead
// of an indeterminate spinner. Returns an object URL for the loaded image.
// `priority: 'high'` is an explicit hint (supported in modern Chrome/Edge;
// harmlessly ignored elsewhere) that this is the one request that matters
// most right now — what the viewer is actually looking at.
async function fetchWithProgress(url, signal, onProgress) {
  const response = await fetch(url, { signal, priority: 'high' });
  if (!response.ok || !response.body) throw new Error('fetch failed: ' + response.status);
  const total = parseInt(response.headers.get('Content-Length') || '0', 10);
  const reader = response.body.getReader();
  const chunks = [];
  let loaded = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    loaded += value.length;
    if (total) onProgress(loaded / total);
  }
  return URL.createObjectURL(new Blob(chunks));
}

// Tracks the in-flight full-quality fetch so a fast next/prev doesn't leave
// multiple "current photo" downloads competing for bandwidth — only the
// photo actually being looked at right now should be fetching at high
// priority; navigating away cancels whatever was still loading for the old
// one. Also tracks the object URL so it can be released once superseded.
let currentLoadController = null;
let currentObjectUrl = null;

function renderLightbox() {
  const post = lbList[lbIndex];
  if (!post) return;

  if (currentLoadController) currentLoadController.abort();
  currentLoadController = new AbortController();
  const { signal } = currentLoadController;

  const imgEl = document.getElementById('lightboxImg');
  const progressEl = document.getElementById('lightboxProgress');
  const progressBar = document.getElementById('lightboxProgressBar');
  const thumbSrc = post.thumb || post.image;
  const previewSrc = post.preview || thumbSrc;

  // Show the already-cached, lightweight thumbnail immediately (blurred, as
  // a deliberate "still loading" cue) so there's no blank/slow flash, then
  // sharpen to the real preview once it's actually loaded (same aspect
  // ratio, so no visible size jump when it swaps in).
  imgEl.src = thumbSrc;
  imgEl.alt = post.caption || '';

  if (previewSrc !== thumbSrc) {
    imgEl.classList.add('is-loading');
    progressBar.style.width = '0%';
    progressEl.hidden = false;

    fetchWithProgress(previewSrc, signal, (fraction) => {
      progressBar.style.width = (fraction * 100).toFixed(1) + '%';
    }).then((objectUrl) => {
      if (lbList[lbIndex] !== post) { URL.revokeObjectURL(objectUrl); return; }
      if (currentObjectUrl) URL.revokeObjectURL(currentObjectUrl);
      currentObjectUrl = objectUrl;
      imgEl.src = objectUrl;
      imgEl.classList.remove('is-loading');
      progressEl.hidden = true;
    }).catch((err) => {
      if (lbList[lbIndex] !== post || err.name === 'AbortError') return;
      // Streamed fetch failed for some reason (e.g. no CORS/byte-range
      // support in some hosting edge case) — fall back to a plain <img>
      // load; no progress bar, but the photo still displays.
      const fallback = new Image();
      fallback.onload = () => {
        if (lbList[lbIndex] === post) {
          imgEl.src = previewSrc;
          imgEl.classList.remove('is-loading');
        }
      };
      fallback.src = previewSrc;
      progressEl.hidden = true;
    });
  } else {
    imgEl.classList.remove('is-loading');
  }

  preloadPreview(lbList[lbIndex - 1]);
  preloadPreview(lbList[lbIndex + 1]);

  const peopleEl = document.getElementById('lightboxPeople');
  peopleEl.innerHTML = '';
  for (const handle of post.people || []) {
    peopleEl.appendChild(buildHandleBubble(handle));
  }

  const captionEl = document.getElementById('lightboxCaption');
  captionEl.textContent = post.caption || '';
  captionEl.hidden = !post.caption;

  document.getElementById('lightboxEvent').textContent = post.event || '';
  document.getElementById('lightboxDate').textContent = formatDate(post.date);
  document.getElementById('lightboxFull').href =
    'photo.html?src=' + encodeURIComponent(post.image);

  // Dim nav arrows at the ends.
  document.getElementById('lightboxPrev').disabled = lbIndex <= 0;
  document.getElementById('lightboxNext').disabled = lbIndex >= lbList.length - 1;
}

function openLightbox(list, index) {
  lbList = list;
  lbIndex = index;
  renderLightbox();
  lightbox.hidden = false;
  document.body.style.overflow = 'hidden';
  document.addEventListener('keydown', onLightboxKey);
}

function closeLightbox() {
  lightbox.hidden = true;
  document.body.style.overflow = '';
  document.removeEventListener('keydown', onLightboxKey);
  if (currentLoadController) currentLoadController.abort();
}

function lightboxStep(delta) {
  const next = lbIndex + delta;
  if (next < 0 || next >= lbList.length) return;
  lbIndex = next;
  renderLightbox();
}

function onLightboxKey(e) {
  if (e.key === 'Escape') closeLightbox();
  else if (e.key === 'ArrowLeft') lightboxStep(-1);
  else if (e.key === 'ArrowRight') lightboxStep(1);
}

if (lightbox) {
  document.getElementById('lightboxClose').addEventListener('click', closeLightbox);
  document.getElementById('lightboxPrev').addEventListener('click', () => lightboxStep(-1));
  document.getElementById('lightboxNext').addEventListener('click', () => lightboxStep(1));
  // Click on the backdrop (not the figure or controls) closes.
  lightbox.addEventListener('click', (e) => {
    if (e.target === lightbox) closeLightbox();
  });
}

// Mobile equivalent of the desktop hover-expand effect: as a finger drags
// across the grid/carousel, whichever thumbnail is currently under it gets
// the same "touch-active" expanded treatment as :hover, one at a time.
function setupTouchExpand() {
  let activeLink = null;

  function setActive(link) {
    if (link === activeLink) return;
    if (activeLink) activeLink.classList.remove('touch-active');
    activeLink = link;
    if (activeLink) activeLink.classList.add('touch-active');
  }

  document.addEventListener('touchmove', (e) => {
    const touch = e.touches[0];
    if (!touch) return;
    const el = document.elementFromPoint(touch.clientX, touch.clientY);
    const link = el && el.closest('.post-link');
    const inScope = link && link.closest('.gallery');
    setActive(inScope ? link : null);
  }, { passive: true });

  document.addEventListener('touchend', () => setActive(null));
  document.addEventListener('touchcancel', () => setActive(null));
}

if (gallery) {
  setupTouchExpand();

  fetch('posts.json')
    .then((res) => res.json())
    .then((posts) => {
      FEATURED = posts.filter((p) => p.featured);
      // Photos listed in photos/meta/featured-order.txt (which carry an `order`
      // index) come first in that order; the rest keep date order (the array
      // is already sorted newest-first, and Array.sort is stable).
      FEATURED.sort((a, b) => {
        const ao = a.order, bo = b.order;
        if (ao != null && bo != null) return ao - bo;
        if (ao != null) return -1;
        if (bo != null) return 1;
        return 0;
      });
      setupFeaturedRow(FEATURED);

      if (!posts.length) {
        gallery.innerHTML = '<p class="gallery-empty">No photos yet.</p>';
        return;
      }

      setupEventFilter(posts, (selected) => renderGrid(posts, selected));
      renderGrid(posts, new Set());
    })
    .catch(() => {
      gallery.innerHTML = '<p class="gallery-empty">Could not load posts.json (run <code>python3 build.py</code>, and serve over http:// rather than file://).</p>';
    });
}
