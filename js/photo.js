const yearEl = document.getElementById('year');
if (yearEl) yearEl.textContent = new Date().getFullYear();

const content = document.getElementById('photoContent');
const params = new URLSearchParams(window.location.search);
const src = params.get('src');

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric', month: 'long', day: 'numeric',
  });
}

// Streams a URL with real download progress (same technique as the
// lightbox), returning an object URL once fully loaded.
async function fetchWithProgress(url, onProgress) {
  const response = await fetch(url, { priority: 'high' });
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

if (!src) {
  content.innerHTML = '<p class="gallery-empty">No photo specified.</p>';
} else {
  fetch('posts.json')
    .then((res) => res.json())
    .then((posts) => {
      const post = posts.find((p) => p.image === src);
      if (!post) {
        content.innerHTML = '<p class="gallery-empty">Photo not found.</p>';
        return;
      }

      const people = post.people || [];
      document.title = (post.caption || people.join(', ') || 'Photo') + ' | Photography Portfolio';

      // Send "back" to whichever gallery this photo actually belongs to.
      const backLink = document.querySelector('.back-link');
      if (backLink && post.collection === 'other') {
        backLink.href = 'other-work.html';
        backLink.textContent = '← Back to other work';
      }

      content.innerHTML = '';

      // Blur-up: show the already-cached thumbnail immediately (blurred),
      // then stream in the full-resolution original with a progress bar,
      // swapping to the sharp version once it's fully loaded — same
      // technique as the lightbox, so this page doesn't feel like a plain
      // slow <img> load in comparison.
      const wrap = document.createElement('div');
      wrap.className = 'photo-full-wrap';

      const progressEl = document.createElement('div');
      progressEl.className = 'photo-progress';
      progressEl.hidden = true;
      const progressBar = document.createElement('div');
      progressBar.className = 'photo-progress-bar';
      progressEl.appendChild(progressBar);

      const img = document.createElement('img');
      img.className = 'photo-full';
      img.alt = post.caption || people.join(', ');

      const thumbSrc = post.thumb || post.image;
      const fullSrc = post.image;
      img.src = thumbSrc;

      wrap.appendChild(progressEl);
      wrap.appendChild(img);
      content.appendChild(wrap);

      if (fullSrc !== thumbSrc) {
        img.classList.add('is-loading');
        progressBar.style.width = '0%';
        progressEl.hidden = false;

        fetchWithProgress(fullSrc, (fraction) => {
          progressBar.style.width = (fraction * 100).toFixed(1) + '%';
        }).then((objectUrl) => {
          img.src = objectUrl;
          img.classList.remove('is-loading');
          progressEl.hidden = true;
        }).catch(() => {
          // Streamed fetch failed for some reason — fall back to a plain
          // <img> load; no progress bar, but the photo still displays.
          const fallback = new Image();
          fallback.onload = () => {
            img.src = fullSrc;
            img.classList.remove('is-loading');
          };
          fallback.src = fullSrc;
          progressEl.hidden = true;
        });
      }

      if (people.length) {
        const peopleEl = document.createElement('div');
        peopleEl.className = 'photo-people';
        for (const handle of people) {
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

          const avatarSrc = 'avatars/' + cleanHandle.toLowerCase() + '.jpg';

          // Larger decorative hover preview of the same avatar — see
          // main.js's buildHandleBubble for why it's pointer-events: none
          // and why its src is only set once the small avatar confirms the
          // image exists.
          const preview = document.createElement('span');
          preview.className = 'people-bubble-preview';
          const previewImg = document.createElement('img');
          previewImg.alt = '';
          preview.appendChild(previewImg);

          const avatar = document.createElement('img');
          avatar.className = 'people-bubble-avatar';
          avatar.alt = '';
          avatar.addEventListener('load', () => {
            bubble.classList.remove('no-avatar');
            previewImg.src = avatarSrc;
          }, { once: true });
          avatar.addEventListener('error', () => avatar.remove(), { once: true });
          avatar.src = avatarSrc;
          bubble.appendChild(avatar);
          bubble.appendChild(preview);

          peopleEl.appendChild(bubble);
        }
        content.appendChild(peopleEl);
      }

      if (post.event) {
        const eventEl = document.createElement('p');
        eventEl.className = 'photo-event';
        eventEl.textContent = post.event;
        content.appendChild(eventEl);
      }

      if (post.caption) {
        const caption = document.createElement('p');
        caption.className = 'photo-caption';
        caption.textContent = post.caption;
        content.appendChild(caption);
      }

      const dateEl = document.createElement('p');
      dateEl.className = 'photo-date';
      dateEl.textContent = formatDate(post.date);
      content.appendChild(dateEl);

      const exifOrder = [
        ['camera', 'Camera'],
        ['focal_length', 'Focal length'],
        ['aperture', 'Aperture'],
        ['shutter', 'Shutter'],
        ['iso', 'ISO'],
      ];
      const exifPairs = post.exif
        ? exifOrder.filter(([key]) => post.exif[key])
        : [];
      if (exifPairs.length) {
        const exifEl = document.createElement('div');
        exifEl.className = 'photo-exif';
        for (const [key, label] of exifPairs) {
          const item = document.createElement('div');
          item.textContent = label + ': ';
          const value = document.createElement('span');
          // Strip the redundant "ISO " prefix from build.py's raw value
          // since the label already says "ISO".
          value.textContent = key === 'iso'
            ? post.exif[key].replace(/^ISO\s*/, '')
            : post.exif[key];
          item.appendChild(value);
          exifEl.appendChild(item);
        }
        content.appendChild(exifEl);
      }
    })
    .catch(() => {
      content.innerHTML = '<p class="gallery-empty">Could not load photo data.</p>';
    });
}
