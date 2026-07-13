document.getElementById('year').textContent = new Date().getFullYear();

const gallery = document.getElementById('gallery');

if (gallery) {
  fetch('posts.json')
    .then((res) => res.json())
    .then((posts) => {
      if (!posts.length) {
        gallery.innerHTML = '<p class="gallery-empty">No photos yet.</p>';
        return;
      }
      gallery.innerHTML = '';
      for (const post of posts) {
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
        dateEl.textContent = new Date(post.date).toLocaleDateString(undefined, {
          year: 'numeric', month: 'long', day: 'numeric',
        });
        figure.appendChild(dateEl);

        gallery.appendChild(figure);
      }
    })
    .catch(() => {
      gallery.innerHTML = '<p class="gallery-empty">Could not load posts.json (run <code>node build.js</code>, and serve over http:// rather than file://).</p>';
    });
}
