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
          const bubble = document.createElement('span');
          bubble.className = 'people-bubble';
          bubble.textContent = handle;
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
    })
    .catch(() => {
      content.innerHTML = '<p class="gallery-empty">Could not load photo data.</p>';
    });
}
