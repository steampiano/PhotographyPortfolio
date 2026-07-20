const yearEl = document.getElementById('year');
if (yearEl) yearEl.textContent = new Date().getFullYear();

// Mobile hamburger toggle for the header nav (see the 480px media query in
// style.css) — hidden entirely on wider viewports via CSS, so this is just
// dead-weight-cheap to always run.
function setupMobileNav() {
  const toggle = document.getElementById('navToggle');
  const nav = document.getElementById('siteNav');
  if (!toggle || !nav) return;

  function closeMenu() {
    nav.classList.remove('open');
    toggle.setAttribute('aria-expanded', 'false');
  }

  toggle.addEventListener('click', () => {
    const open = nav.classList.toggle('open');
    toggle.setAttribute('aria-expanded', String(open));
  });

  document.addEventListener('click', (e) => {
    if (nav.classList.contains('open') && !nav.contains(e.target) && !toggle.contains(e.target)) {
      closeMenu();
    }
  });

  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') closeMenu();
  });
}
setupMobileNav();

const gallery = document.getElementById('gallery');

// The two navigable collections, kept up to date as things render. The lightbox
// scrolls through whichever collection a photo was opened from.
let FEATURED = [];
let GRID = [];

// Per-handle border accent colors (see build.py's compute_avatar_color) —
// kicked off immediately since it's a tiny file, so by the time anyone
// actually opens a lightbox (which requires a prior click) it's
// essentially always already resolved.
let AVATAR_COLORS = {};
fetch('avatars/colors.json').then((res) => (res.ok ? res.json() : {})).then((data) => {
  AVATAR_COLORS = data;
}).catch(() => {});

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

// On hover (desktop only), a thumbnail expands to its real (uncropped) aspect
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
  // Set on the whole figure (not the img) so custom-property inheritance
  // reaches both the img AND the .post-meta block below it — the meta
  // slides along with the expansion (see .post-meta's hover CSS) and needs
  // --expand-w for the same math.
  const widthPct = ratio >= 1 ? shortPct * ratio : shortPct;
  const target = img.closest('figure') || img;
  target.style.setProperty('--expand-w', widthPct.toFixed(2) + '%');
  target.style.setProperty('--expand-ratio', ratio.toFixed(4));
}

// A handle bubble linking to the matching Instagram profile. Just the
// handle text — border color hints at the photo (see AVATAR_COLORS) and
// hovering reveals the actual picture (see .people-bubble-preview); if no
// avatar is cached for this handle, it's just a plain neutral-border pill.
function buildHandleBubble(handle) {
  const cleanHandle = handle.replace(/^@/, '');
  const bubble = document.createElement('a');
  bubble.className = 'people-bubble';
  bubble.href = 'https://instagram.com/' + cleanHandle;
  bubble.target = '_blank';
  bubble.rel = 'noopener noreferrer';

  const label = document.createElement('span');
  label.className = 'people-bubble-label';
  label.textContent = handle;
  bubble.appendChild(label);

  const accent = AVATAR_COLORS[cleanHandle.toLowerCase()];
  if (accent) bubble.style.setProperty('--accent', accent);

  // Only build a preview at all for handles AVATAR_COLORS already confirms
  // have a cached avatar (that map only has entries for images that exist —
  // see build.py's write_avatar_colors), and don't fetch the image itself
  // until the pill is actually hovered. Some photos tag up to 9 people;
  // downloading every one of their avatars up front, on the off chance any
  // single one gets hovered, wasted a real request per tagged person on
  // every lightbox open for a decorative feature most visitors never
  // trigger. avatarLoaded guards against re-setting src on repeat hovers.
  if (accent) {
    const preview = document.createElement('span');
    preview.className = 'people-bubble-preview';
    const previewImg = document.createElement('img');
    previewImg.alt = '';
    previewImg.decoding = 'async';
    preview.appendChild(previewImg);
    bubble.appendChild(preview);

    let avatarLoaded = false;
    bubble.addEventListener('mouseenter', () => {
      if (avatarLoaded) return;
      avatarLoaded = true;
      previewImg.src = 'avatars/' + cleanHandle.toLowerCase() + '.jpg';
    });
  }

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
  img.decoding = 'async';
  link.appendChild(img);
  figure.appendChild(link);

  // After appending, not before — applyExpandSize writes the expansion
  // custom properties onto the figure via img.closest('figure'), which
  // is null for a detached img (a cached image can be `complete`
  // synchronously, before it was ever attached).
  if (img.complete && img.naturalWidth) {
    applyExpandSize(img);
  } else {
    img.addEventListener('load', () => applyExpandSize(img), { once: true });
  }

  appendPostMeta(figure, post);
  return figure;
}

// Event badge + caption + date, shared by both the Recent Work grid and the
// Featured row. Wrapped in one .post-meta block so the grid's hover CSS can
// slide all of it as a unit, tracking the expanding image's bottom-left
// corner.
function appendPostMeta(figure, post) {
  const meta = document.createElement('div');
  meta.className = 'post-meta';

  if (post.event) {
    const eventEl = document.createElement('span');
    eventEl.className = 'post-event';
    eventEl.textContent = post.event;
    meta.appendChild(eventEl);
  }

  if (post.caption) {
    // A classed span, not <figcaption> — figcaption is only valid as a
    // direct child of <figure>, and this sits inside the .post-meta div.
    const caption = document.createElement('span');
    caption.className = 'post-caption';
    caption.textContent = post.caption;
    meta.appendChild(caption);
  }

  const dateEl = document.createElement('span');
  dateEl.className = 'post-date';
  dateEl.textContent = formatDate(post.date);
  meta.appendChild(dateEl);

  figure.appendChild(meta);
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
  // No loading="lazy" here (unlike the grid figure above) — layout()
  // below explicitly waits for every one of these to load so it can read
  // their real naturalWidth/Height for the justified-row math; lazily
  // deferring an off-screen carousel slide would stall that wait instead
  // of just skipping ahead.
  img.decoding = 'async';
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
  // The carousel branch of layout(), the wrap-around clones, and the
  // teleport logic below must all agree on when "carousel mode" is active,
  // so they all key off the same media query as .featured-row's mobile CSS
  // (the row's own clientWidth can dip under 640 while the viewport — and
  // therefore the CSS — hasn't, e.g. viewport 641-688 minus page padding).
  const carouselMode = window.matchMedia('(max-width: 640px)');

  const items = featuredPosts.map((post) => {
    const figure = buildFeaturedItem(post);
    row.appendChild(figure);
    return { figure, img: figure.querySelector('img'), ratio: 1, clone: false };
  });

  // Infinite wrap for the mobile carousel: copies of the last few photos
  // sit before the first (so the very first swipe can go backwards) and
  // copies of the first few sit after the last. Whenever the scroll comes
  // to rest on a copy it teleports to the real counterpart (see
  // teleportIfOnClone below) — the classic clone-and-teleport loop. Four
  // slides of runway per side is generous headroom for a fast fling that
  // travels multiple slides in one gesture before it decelerates — with
  // too little runway, that deceleration can slam into the physical end
  // of the scrollable strip and stop dead instead of gliding to a rest,
  // which is its own kind of jump, independent of the teleport itself
  // (see teleportIfOnClone's comment for why the teleport only ever runs
  // once scrolling has fully stopped, never mid-gesture). buildFeaturedItem
  // looks the post up by image when clicked, so a clone opens the same
  // lightbox slide as the real one. Hidden outside carousel mode via CSS
  // (.featured-clone) and skipped by the justified packing, so desktop
  // never sees duplicates.
  const cloneCount = featuredPosts.length >= 2 ? Math.min(4, featuredPosts.length - 1) : 0;
  if (cloneCount) {
    const firstRealFigure = items[0].figure;
    const leadEntries = [];
    for (let i = featuredPosts.length - cloneCount; i < featuredPosts.length; i++) {
      const fig = buildFeaturedItem(featuredPosts[i]);
      fig.classList.add('featured-clone');
      row.insertBefore(fig, firstRealFigure);
      leadEntries.push({ figure: fig, img: fig.querySelector('img'), ratio: 1, clone: true, realIdx: i });
    }
    items.unshift(...leadEntries);

    for (let i = 0; i < cloneCount; i++) {
      const fig = buildFeaturedItem(featuredPosts[i]);
      fig.classList.add('featured-clone');
      row.appendChild(fig);
      items.push({ figure: fig, img: fig.querySelector('img'), ratio: 1, clone: true, realIdx: i });
    }
  }

  // The scrollLeft at which `it` sits centered (its scroll-snap rest
  // position). getBoundingClientRect instead of offsetLeft because the
  // figures are position: relative, so offsetLeft isn't row-relative.
  function snapLeftOf(it) {
    const rowRect = row.getBoundingClientRect();
    const rect = it.figure.getBoundingClientRect();
    return row.scrollLeft + (rect.left - rowRect.left) - (row.clientWidth - rect.width) / 2;
  }

  function centerOn(it) {
    row.scrollLeft = snapLeftOf(it);
  }

  function targetRowHeight() {
    // In carousel mode this is one full-slide photo at a time, not a dense
    // packed row, so it gets a much more generous height than the old
    // multi-column grid used.
    if (carouselMode.matches) return 420;
    return row.clientWidth < 560 ? 170 : 260;
  }

  function layout() {
    const containerWidth = row.clientWidth;
    if (!containerWidth) return;
    const rowHeight = targetRowHeight();

    // Mobile: the justified multi-per-row packing below can still end up
    // squeezing two small thumbnails onto a row next to a full-width one,
    // which reads as cramped/inconsistent on a narrow screen. In carousel
    // mode, skip packing entirely — one photo per "row", each kept at its
    // own aspect ratio (capped so it never needs its own horizontal scroll
    // inside what's otherwise a one-swipe-per-photo strip), and the row
    // itself becomes a horizontally swipeable carousel via CSS.
    if (carouselMode.matches) {
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
      // Start on the real first photo — without this the row would open
      // scrolled to the leading wrap-around copies of the LAST photos.
      if (cloneCount) centerOn(items[cloneCount]);
      return;
    }

    const MAX_SCALE = 1.3;
    const realItems = items.filter((it) => !it.clone);

    let i = 0;
    while (i < realItems.length) {
      // Grow the row one photo at a time. Once adding the next photo would
      // overflow the container, pick whichever of "stop here" or "include
      // it" lands closer to the target row height, instead of always
      // overflowing-then-shrinking. That's what made full rows consistently
      // shorter than the stretched-out trailing row.
      let sumRatios = realItems[i].ratio;
      let count = 1;
      let j = i + 1;

      while (j < realItems.length) {
        const sumWithNext = sumRatios + realItems[j].ratio;
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

      const rowItems = realItems.slice(i, i + count);
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

  // The wrap-around teleport. Only ever runs once scrolling has fully
  // stopped (a plain debounce — scroll events stop firing for a beat —
  // rather than the scrollend event, which iOS Safari has historically
  // lacked), never mid-gesture or mid-momentum. An earlier version also
  // corrected on every scroll event, reasoning that a settle-only check
  // could miss a fast swipe entirely — but firing mid-flight fights the
  // browser's own momentum/snap physics (forcing scrollLeft while iOS is
  // still decelerating a fling), which is what made crossing the wrap
  // point feel jumpy rather than smooth. The actual fix for "ran out of
  // runway" is just more clones (see cloneCount above), not correcting
  // sooner — settling is what scroll-snap already does natively and
  // smoothly; this only ever nudges the final rest position afterward,
  // silently, since a clone and its real counterpart are pixel-identical.
  //
  // The debounce itself is short (see the setTimeout below) — every slide
  // now has scroll-snap-stop: always (see CSS), so a swipe can no longer
  // sail past several slides before coming to rest the way it used to;
  // scroll events stop firing very shortly after a real settle, so the
  // wait before the loop completes can stay short too without mistaking
  // still-decelerating motion for a stop.
  function teleportIfOnClone() {
    if (!cloneCount || !carouselMode.matches) return;
    const rowRect = row.getBoundingClientRect();
    const centerX = rowRect.left + rowRect.width / 2;
    let nearest = null;
    let nearestDist = Infinity;
    for (const it of items) {
      const rect = it.figure.getBoundingClientRect();
      const dist = Math.abs(rect.left + rect.width / 2 - centerX);
      if (dist < nearestDist) {
        nearestDist = dist;
        nearest = it;
      }
    }
    if (!nearest || !nearest.clone) return;
    row.scrollLeft += snapLeftOf(items[cloneCount + nearest.realIdx]) - snapLeftOf(nearest);
  }

  let settleTimer;
  row.addEventListener('scroll', () => {
    if (!cloneCount || !carouselMode.matches) return;
    clearTimeout(settleTimer);
    settleTimer = setTimeout(teleportIfOnClone, 60);
  }, { passive: true });

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

  // Click on the backdrop (not the image, actual text/links, or controls)
  // closes. Excluding the whole .lightbox-meta block (an earlier version of
  // this fix) was too broad — .lightbox-people is a flex row with gaps
  // between the handle bubbles, and .lightbox-meta itself has gaps between
  // its lines, all of which are still tint even though they sit inside that
  // wrapper. Targeting the actual leaf content elements (image, links,
  // paragraphs, the EXIF panel) instead treats every one of those in-between
  // gaps as backdrop too, while still protecting real text/links from
  // closing when clicked directly.
  lightbox.addEventListener('click', (e) => {
    if (e.target.closest('.lightbox-img, .lightbox-info-panel, a, p, button')) return;
    closeLightbox();
  });
}

// (There is deliberately no touch equivalent of the desktop hover-expand —
// an earlier version expanded whichever thumbnail was under a dragging
// finger, but on a phone that just made scrolling feel busy. A tap goes
// straight to the lightbox; the expand effect is hover/desktop-only.)

if (gallery) {
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
