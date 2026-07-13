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

function buildPostFigure(post, source) {
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
    const list = source === 'featured' ? FEATURED : GRID;
    const idx = list.findIndex((p) => p.image === post.image);
    if (idx !== -1) openLightbox(list, idx);
  });

  const img = document.createElement('img');
  img.src = post.thumb || post.image;
  img.alt = post.caption || (post.people ? post.people.join(', ') : '');
  img.loading = 'lazy';
  link.appendChild(img);
  figure.appendChild(link);

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

  return figure;
}

function setupCarousel(featuredPosts) {
  const section = document.getElementById('highlights-section');
  const track = document.getElementById('carouselTrack');
  const prevBtn = document.getElementById('carouselPrev');
  const nextBtn = document.getElementById('carouselNext');
  if (!section || !track || !featuredPosts.length) return;

  section.hidden = false;
  track.innerHTML = '';
  for (const post of featuredPosts) {
    const slide = document.createElement('div');
    slide.className = 'carousel-slide';
    slide.appendChild(buildPostFigure(post, 'featured'));
    track.appendChild(slide);
  }

  const slides = () => [...track.children];

  // Explicit current index gives reliable arrow stepping (not thrown off by an
  // in-flight smooth scroll); manual swipes resync it once scrolling settles.
  let index = 0;

  function nearestIndex() {
    const list = slides();
    let best = 0, bestDist = Infinity;
    list.forEach((el, i) => {
      const d = Math.abs(el.offsetLeft - track.scrollLeft);
      if (d < bestDist) { bestDist = d; best = i; }
    });
    return best;
  }

  function goTo(i) {
    const list = slides();
    index = Math.max(0, Math.min(i, list.length - 1));
    track.scrollTo({ left: list[index].offsetLeft, behavior: 'smooth' });
  }

  // Dim/disable an arrow when there's nothing further in that direction.
  function updateArrows() {
    const maxScroll = track.scrollWidth - track.clientWidth;
    prevBtn.disabled = track.scrollLeft <= 2;
    nextBtn.disabled = track.scrollLeft >= maxScroll - 2;
  }

  let resyncTimer;
  prevBtn.addEventListener('click', () => goTo(index - 1));
  nextBtn.addEventListener('click', () => goTo(index + 1));
  track.addEventListener('scroll', () => {
    window.requestAnimationFrame(updateArrows);
    clearTimeout(resyncTimer);
    resyncTimer = setTimeout(() => { index = nearestIndex(); }, 120);
  });
  window.addEventListener('resize', updateArrows);
  updateArrows();
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
    gallery.appendChild(buildPostFigure(post, 'grid'));
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

function renderLightbox() {
  const post = lbList[lbIndex];
  if (!post) return;

  document.getElementById('lightboxImg').src = post.preview || post.thumb || post.image;
  document.getElementById('lightboxImg').alt = post.caption || '';

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
    const inScope = link && (link.closest('.gallery') || link.closest('.carousel-slide'));
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
      setupCarousel(FEATURED);

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
