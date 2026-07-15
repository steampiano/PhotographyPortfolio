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

// Turns a post's `exif` object (already human-readable strings from
// build.py, e.g. {aperture: "f/1.4", iso: "ISO 800"}) into ordered
// [label, value] pairs for display. The "ISO " prefix is stripped here since
// it's redundant next to an "ISO" label; build.py keeps it in the raw value
// because that copy is also used standalone (no label) on the photo page.
function exifDisplayFields(exif) {
  if (!exif) return [];
  const order = [
    ['camera', 'Camera'],
    ['focal_length', 'Focal length'],
    ['aperture', 'Aperture'],
    ['shutter', 'Shutter'],
    ['iso', 'ISO'],
  ];
  const pairs = [];
  for (const [key, label] of order) {
    if (!exif[key]) continue;
    const value = key === 'iso' ? exif[key].replace(/^ISO\s*/, '') : exif[key];
    pairs.push([label, value]);
  }
  return pairs;
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

// A handle bubble linking to the matching Instagram profile. Tries to show
// that person's cached avatar (see build.py's fetch_avatar) as the pill's
// right-side cap; if none exists yet (or the image 404s), falls back to a
// plain text-only pill via the .no-avatar class.
function buildHandleBubble(handle) {
  const cleanHandle = handle.replace(/^@/, '');
  const bubble = document.createElement('a');
  bubble.className = 'people-bubble no-avatar';
  bubble.href = 'https://instagram.com/' + cleanHandle;
  bubble.target = '_blank';
  bubble.rel = 'noopener noreferrer';

  const label = document.createElement('span');
  label.className = 'people-bubble-label';
  label.textContent = handle;
  bubble.appendChild(label);

  const avatar = document.createElement('img');
  avatar.className = 'people-bubble-avatar';
  avatar.alt = '';
  avatar.addEventListener('load', () => bubble.classList.remove('no-avatar'), { once: true });
  avatar.addEventListener('error', () => avatar.remove(), { once: true });
  avatar.src = 'avatars/' + cleanHandle.toLowerCase() + '.jpg';
  bubble.appendChild(avatar);

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
    if (idx !== -1) openLightbox(GRID, idx);
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
    // Below 640 this is one full-slide photo at a time (see layout()'s
    // carousel branch), not a dense packed row, so it gets a much more
    // generous height than the old multi-column grid used.
    if (containerWidth < 640) return 420;
    return containerWidth < 560 ? 170 : 260;
  }

  function layout() {
    const containerWidth = row.clientWidth;
    if (!containerWidth) return;
    const rowHeight = targetRowHeight(containerWidth);

    // Mobile: the justified multi-per-row packing below can still end up
    // squeezing two small thumbnails onto a row next to a full-width one,
    // which reads as cramped/inconsistent on a narrow screen. Below the
    // carousel breakpoint (matches .featured-row's mobile media query),
    // skip packing entirely — one photo per "row", each kept at its own
    // aspect ratio (capped so it never needs its own horizontal scroll
    // inside what's otherwise a one-swipe-per-photo strip), and the row
    // itself becomes a horizontally swipeable carousel via CSS.
    if (containerWidth < 640) {
      const maxWidth = containerWidth * 0.88;
      const maxHeight = window.innerHeight * 0.6;
      for (const it of items) {
        let h = Math.min(rowHeight, maxHeight);
        let w = h * it.ratio;
        if (w > maxWidth) {
          w = maxWidth;
          h = w / it.ratio;
        }
        it.figure.style.width = w + 'px';
        it.img.style.height = h + 'px';
      }
      return;
    }

    const MAX_SCALE = 1.3;

    let i = 0;
    while (i < items.length) {
      // Grow the row one photo at a time. Once adding the next photo would
      // overflow the container, pick whichever of "stop here" or "include
      // it" lands closer to the target row height, instead of always
      // overflowing-then-shrinking. That's what made full rows consistently
      // shorter than the stretched-out trailing row.
      let sumRatios = items[i].ratio;
      let count = 1;
      let j = i + 1;

      while (j < items.length) {
        const sumWithNext = sumRatios + items[j].ratio;
        const widthWithNext = rowHeight * sumWithNext + GAP * count;
        if (widthWithNext >= containerWidth) {
          const scaleWithout = (containerWidth - GAP * (count - 1)) / (rowHeight * sumRatios);
          const scaleWith = (containerWidth - GAP * count) / (rowHeight * sumWithNext);
          if (Math.abs(scaleWith - 1) < Math.abs(scaleWithout - 1)) {
            sumRatios = sumWithNext;
            count++;
          }
          break;
        }
        sumRatios = sumWithNext;
        count++;
        j++;
      }

      const rowItems = items.slice(i, i + count);
      const totalGap = GAP * (count - 1);
      const scale = Math.min((containerWidth - totalGap) / (rowHeight * sumRatios), MAX_SCALE);
      const h = rowHeight * scale;
      for (const it of rowItems) {
        it.figure.style.width = (h * it.ratio) + 'px';
        it.img.style.height = h + 'px';
      }

      i += count;
    }
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
// Persists across next/prev within a lightbox session (reset on close) so
// stepping to another photo keeps the panel open, just with fresh EXIF data,
// instead of snapping shut every time.
let infoPanelOpen = false;

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

  // Shooting info (aperture/shutter/ISO/focal length) is tucked behind the
  // info button rather than shown by default, since it's secondary to the
  // photo itself. If the panel is already open, it stays open across
  // next/prev — only the EXIF data itself refreshes — rather than snapping
  // shut every time you step to another photo.
  const infoBtn = document.getElementById('lightboxInfoBtn');
  const infoPanel = document.getElementById('lightboxInfoPanel');
  const exifList = document.getElementById('lightboxExifList');
  exifList.innerHTML = '';
  for (const [label, value] of exifDisplayFields(post.exif)) {
    const dt = document.createElement('dt');
    dt.textContent = label;
    const dd = document.createElement('dd');
    dd.textContent = value;
    exifList.appendChild(dt);
    exifList.appendChild(dd);
  }
  const hasExif = !!(post.exif && Object.keys(post.exif).length);
  infoBtn.hidden = !hasExif;
  if (!hasExif) infoPanelOpen = false;
  infoPanel.classList.toggle('is-open', infoPanelOpen);
  infoBtn.setAttribute('aria-expanded', String(infoPanelOpen));

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
  infoPanelOpen = false;
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

  const infoBtn = document.getElementById('lightboxInfoBtn');
  const infoPanel = document.getElementById('lightboxInfoPanel');
  infoBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    infoPanelOpen = !infoPanelOpen;
    infoPanel.classList.toggle('is-open', infoPanelOpen);
    infoBtn.setAttribute('aria-expanded', String(infoPanelOpen));
  });

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

  // Which collection this page shows (defaults to "fursuit" for older pages
  // that don't set the attribute) — posts.json holds every collection
  // together, tagged per-post, so each page filters to its own slice.
  const pageCollection = gallery.dataset.collection || 'fursuit';

  fetch('posts.json')
    .then((res) => res.json())
    .then((allPosts) => {
      const posts = allPosts.filter((p) => (p.collection || 'fursuit') === pageCollection);
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
