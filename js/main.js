const yearEl = document.getElementById('year');
if (yearEl) yearEl.textContent = new Date().getFullYear();

const gallery = document.getElementById('gallery');

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric', month: 'long', day: 'numeric',
  });
}

function buildPostFigure(post) {
  const figure = document.createElement('figure');
  figure.className = 'post';

  const link = document.createElement('a');
  link.className = 'post-link';
  link.href = 'photo.html?src=' + encodeURIComponent(post.image);

  const img = document.createElement('img');
  img.src = post.thumb || post.image;
  img.alt = post.caption || '';
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
    slide.appendChild(buildPostFigure(post));
    track.appendChild(slide);
  }

  const scrollByAmount = () => track.clientWidth * 0.9;
  prevBtn.addEventListener('click', () => {
    track.scrollBy({ left: -scrollByAmount(), behavior: 'smooth' });
  });
  nextBtn.addEventListener('click', () => {
    track.scrollBy({ left: scrollByAmount(), behavior: 'smooth' });
  });
}

// Renders the Recent Work grid, showing only posts whose event is in the
// selected set. An empty set means "no filter" — show everything.
function renderGrid(posts, selectedEvents) {
  gallery.innerHTML = '';
  const visible = selectedEvents.size === 0
    ? posts
    : posts.filter((p) => p.event && selectedEvents.has(p.event));

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

  // Close the panel when clicking outside it.
  document.addEventListener('click', (e) => {
    if (!wrap.contains(e.target)) {
      panel.hidden = true;
      btn.setAttribute('aria-expanded', 'false');
    }
  });
}

if (gallery) {
  fetch('posts.json')
    .then((res) => res.json())
    .then((posts) => {
      const featuredPosts = posts.filter((p) => p.featured);
      // Photos listed in photos/featured-order.txt (which carry an `order`
      // index) come first in that order; the rest keep date order (the array
      // is already sorted newest-first, and Array.sort is stable).
      featuredPosts.sort((a, b) => {
        const ao = a.order, bo = b.order;
        if (ao != null && bo != null) return ao - bo;
        if (ao != null) return -1;
        if (bo != null) return 1;
        return 0;
      });
      setupCarousel(featuredPosts);

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
