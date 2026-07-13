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

  const img = document.createElement('img');
  img.src = post.image;
  img.alt = post.caption || '';
  img.loading = 'lazy';
  figure.appendChild(img);

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

function setupCarousel(highlightPosts) {
  const section = document.getElementById('highlights-section');
  const track = document.getElementById('carouselTrack');
  const prevBtn = document.getElementById('carouselPrev');
  const nextBtn = document.getElementById('carouselNext');
  if (!section || !track || !highlightPosts.length) return;

  section.hidden = false;
  track.innerHTML = '';
  for (const post of highlightPosts) {
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

if (gallery) {
  fetch('posts.json')
    .then((res) => res.json())
    .then((posts) => {
      const highlightPosts = posts.filter((p) => p.highlight);
      setupCarousel(highlightPosts);

      if (!posts.length) {
        gallery.innerHTML = '<p class="gallery-empty">No photos yet.</p>';
        return;
      }
      gallery.innerHTML = '';
      for (const post of posts) {
        gallery.appendChild(buildPostFigure(post));
      }
    })
    .catch(() => {
      gallery.innerHTML = '<p class="gallery-empty">Could not load posts.json (run <code>python3 build.py</code>, and serve over http:// rather than file://).</p>';
    });
}
