const yearEl = document.getElementById('year');
if (yearEl) yearEl.textContent = new Date().getFullYear();

// Mobile hamburger toggle for the header nav — identical to main.js's
// setupMobileNav (this page loads photo.js instead, so it's duplicated
// rather than shared) — see the 480px media query in style.css.
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

const content = document.getElementById('photoContent');
const params = new URLSearchParams(window.location.search);
const src = params.get('src');

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, {
    year: 'numeric', month: 'long', day: 'numeric',
  });
}

// Per-handle border accent colors — see main.js's identical fetch for why
// this is kicked off immediately rather than awaited.
let AVATAR_COLORS = {};
fetch('avatars/colors.json').then((res) => (res.ok ? res.json() : {})).then((data) => {
  AVATAR_COLORS = data;
}).catch(() => {});

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
      document.title = (post.caption || people.join(', ') || 'Photo') + ' | @aspy7777 Photos';

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
      img.decoding = 'async';

      const thumbSrc = post.thumb || post.image;
      const fullSrc = post.image;
      img.src = thumbSrc;

      wrap.appendChild(progressEl);
      wrap.appendChild(img);
      content.appendChild(wrap);

      // Copies a link straight to the full-resolution .jpg (post.image),
      // same as the lightbox's copy-link button — no lbList/lbIndex to
      // read here since there's only ever one photo on this page. new
      // URL(..., location.href) both makes it absolute (a relative path
      // isn't useful pasted somewhere else) and percent-encodes it
      // correctly (image paths can contain spaces).
      const copyLinkBtn = document.createElement('button');
      copyLinkBtn.type = 'button';
      copyLinkBtn.className = 'photo-copy-link-btn';
      copyLinkBtn.setAttribute('aria-live', 'polite');
      copyLinkBtn.textContent = 'Copy Link';
      wrap.appendChild(copyLinkBtn);

      const copyLinkLabel = copyLinkBtn.textContent;
      let copyLinkResetTimer;
      copyLinkBtn.addEventListener('click', () => {
        const url = new URL(post.image, location.href).href;
        navigator.clipboard.writeText(url).then(() => {
          clearTimeout(copyLinkResetTimer);
          copyLinkBtn.textContent = 'Copied!';
          copyLinkBtn.classList.add('copied');
          copyLinkResetTimer = setTimeout(() => {
            copyLinkBtn.textContent = copyLinkLabel;
            copyLinkBtn.classList.remove('copied');
          }, 1500);
        }).catch(() => {
          // Clipboard API can fail (permissions, insecure context) — no
          // visible fallback UI to offer here, so just no-op rather than
          // throw an unhandled rejection.
        });
      });

      // ---- Zoom controls ----
      // Discrete +/- steps, not a gesture/transform implementation —
      // panning once zoomed is plain native browser scrolling (touch-drag,
      // trackpad, scrollbar), not a custom drag handler. A from-scratch
      // pinch/pan zoom was tried in the lightbox first and turned out too
      // unreliable in practice; this trades that flexibility for something
      // that just works, leaning entirely on scrolling the browser already
      // knows how to do.
      const ZOOM_STEP = 0.5;
      const ZOOM_MAX = 3;
      let zoomLevel = 1;
      let baseWidth = 0;
      let baseHeight = 0;
      let zoomReady = false;

      const zoomControls = document.createElement('div');
      zoomControls.className = 'photo-zoom-controls';

      const zoomOutBtn = document.createElement('button');
      zoomOutBtn.type = 'button';
      zoomOutBtn.className = 'photo-zoom-btn';
      zoomOutBtn.setAttribute('aria-label', 'Zoom out');
      zoomOutBtn.textContent = '−';

      const zoomResetBtn = document.createElement('button');
      zoomResetBtn.type = 'button';
      zoomResetBtn.className = 'photo-zoom-btn';
      zoomResetBtn.setAttribute('aria-label', 'Reset zoom');
      zoomResetBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg>';

      const zoomInBtn = document.createElement('button');
      zoomInBtn.type = 'button';
      zoomInBtn.className = 'photo-zoom-btn';
      zoomInBtn.setAttribute('aria-label', 'Zoom in');
      zoomInBtn.textContent = '+';

      zoomControls.appendChild(zoomOutBtn);
      zoomControls.appendChild(zoomResetBtn);
      zoomControls.appendChild(zoomInBtn);
      wrap.appendChild(zoomControls);

      function updateZoomButtons() {
        zoomOutBtn.disabled = zoomLevel <= 1;
        zoomResetBtn.disabled = zoomLevel <= 1;
        zoomInBtn.disabled = !zoomReady || zoomLevel >= ZOOM_MAX;
      }
      updateZoomButtons();

      function setZoom(newLevel) {
        if (!zoomReady) return;
        newLevel = Math.min(ZOOM_MAX, Math.max(1, newLevel));
        if (newLevel === zoomLevel) return;

        // Keeps whatever's currently centered in the scrollable view still
        // roughly centered after the resize, rather than snapping back to
        // the top-left corner — plain proportional math against scroll
        // position, not pointer/gesture tracking.
        const oldWidth = wrap.scrollWidth || baseWidth;
        const oldHeight = wrap.scrollHeight || baseHeight;
        const centerXFraction = (wrap.scrollLeft + wrap.clientWidth / 2) / oldWidth;
        const centerYFraction = (wrap.scrollTop + wrap.clientHeight / 2) / oldHeight;

        zoomLevel = newLevel;
        const zoomed = zoomLevel > 1;
        wrap.classList.toggle('is-zoomed', zoomed);
        img.classList.toggle('is-zoomed', zoomed);
        img.style.width = (baseWidth * zoomLevel) + 'px';
        img.style.height = (baseHeight * zoomLevel) + 'px';
        updateZoomButtons();

        wrap.scrollLeft = centerXFraction * wrap.scrollWidth - wrap.clientWidth / 2;
        wrap.scrollTop = centerYFraction * wrap.scrollHeight - wrap.clientHeight / 2;
      }

      function initZoomOnce() {
        if (zoomReady) return;
        const rect = img.getBoundingClientRect();
        if (!rect.width || !rect.height) return;
        baseWidth = rect.width;
        baseHeight = rect.height;
        // Freezes the "viewport" at its natural (unzoomed) size — without
        // this, growing the image inside would just grow this wrapper
        // along with it (a plain block element sizes to fit its content by
        // default), and nothing would ever overflow enough to scroll.
        wrap.style.width = baseWidth + 'px';
        wrap.style.height = baseHeight + 'px';
        zoomReady = true;
        updateZoomButtons();
      }

      if (img.complete && img.naturalWidth) {
        initZoomOnce();
      } else {
        img.addEventListener('load', initZoomOnce, { once: true });
      }

      zoomOutBtn.addEventListener('click', () => setZoom(zoomLevel - ZOOM_STEP));
      zoomInBtn.addEventListener('click', () => setZoom(zoomLevel + ZOOM_STEP));
      zoomResetBtn.addEventListener('click', () => setZoom(1));

      let zoomResizeTimer;
      window.addEventListener('resize', () => {
        clearTimeout(zoomResizeTimer);
        zoomResizeTimer = setTimeout(() => {
          // The frozen base size no longer matches what natural (unzoomed)
          // sizing would produce at the new viewport width — reset to a
          // clean 1x and re-measure, rather than keep stale dimensions.
          zoomLevel = 1;
          zoomReady = false;
          wrap.classList.remove('is-zoomed');
          img.classList.remove('is-zoomed');
          img.style.width = '';
          img.style.height = '';
          wrap.style.width = '';
          wrap.style.height = '';
          initZoomOnce();
        }, 150);
      });

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

          // Larger decorative hover preview of the avatar, only fetched on
          // first hover — see main.js's buildHandleBubble for why.
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
