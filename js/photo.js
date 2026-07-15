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

      const img = document.createElement('img');
      img.src = post.image;
      img.alt = post.caption || people.join(', ');
      img.className = 'photo-full';
      content.appendChild(img);

      if (people.length) {
        const peopleEl = document.createElement('div');
        peopleEl.className = 'photo-people';
        for (const handle of people) {
          const bubble = document.createElement('a');
          bubble.className = 'people-bubble';
          bubble.textContent = handle;
          bubble.href = 'https://instagram.com/' + handle.replace(/^@/, '');
          bubble.target = '_blank';
          bubble.rel = 'noopener noreferrer';
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
