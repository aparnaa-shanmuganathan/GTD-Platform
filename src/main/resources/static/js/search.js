/*
 * Global search / command palette client-side wiring (Phase 10, SEARCH).
 * Shared by every workspace page (fragments/searchmodal.html supplies the
 * trigger button + modal markup this file drives) - one script, not
 * duplicated per workspace.
 */
(function () {
  'use strict';

  var activeIndex = 0;
  var currentResults = [];
  var debounceTimer = null;

  function qs(id) {
    return document.getElementById(id);
  }

  function csrfMeta(name) {
    var el = document.querySelector('meta[name="' + name + '"]');
    return el ? el.content : null;
  }

  function escapeHtml(value) {
    if (value == null) {
      return '';
    }
    return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function isModalOpen() {
    var backdrop = qs('searchBackdrop');
    return backdrop && backdrop.classList.contains('open');
  }

  function openModal() {
    var backdrop = qs('searchBackdrop');
    if (!backdrop) {
      return;
    }
    backdrop.classList.add('open');
    qs('searchInput').value = '';
    qs('searchInput').focus();
    runSearch('');
  }

  function closeModal() {
    var backdrop = qs('searchBackdrop');
    if (backdrop) {
      backdrop.classList.remove('open');
    }
  }

  function renderResults(results) {
    currentResults = results || [];
    activeIndex = 0;
    var container = qs('searchResults');
    if (currentResults.length === 0) {
      container.innerHTML = '<div class="search-empty">No matches.</div>';
      return;
    }
    container.innerHTML = currentResults.map(function (item, i) {
      return '<button type="button" class="search-result-item" data-index="' + i + '" data-active="' + (i === 0) + '">'
          + '<span style="flex:1;">' + escapeHtml(item.label) + '</span>'
          + '<span class="nav-tag">' + escapeHtml(item.group) + '</span>'
          + '</button>';
    }).join('');

    Array.prototype.forEach.call(container.querySelectorAll('.search-result-item'), function (el) {
      el.addEventListener('click', function () {
        activateResult(parseInt(el.getAttribute('data-index'), 10));
      });
    });
  }

  function setActive(index) {
    var items = Array.prototype.slice.call(document.querySelectorAll('.search-result-item'));
    if (items.length === 0) {
      return;
    }
    activeIndex = (index + items.length) % items.length;
    items.forEach(function (el, i) {
      el.setAttribute('data-active', String(i === activeIndex));
    });
    items[activeIndex].scrollIntoView({ block: 'nearest' });
  }

  function activateResult(index) {
    var item = currentResults[index];
    if (!item) {
      return;
    }
    if (item.key === '__theme') {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
      closeModal();
      return;
    }
    if (item.url) {
      window.location.href = item.url;
    }
  }

  function runSearch(query) {
    var headers = { 'Content-Type': 'application/json' };
    var csrfHeaderName = csrfMeta('_csrf_header');
    var csrfToken = csrfMeta('_csrf');
    if (csrfHeaderName && csrfToken) {
      headers[csrfHeaderName] = csrfToken;
    }
    fetch('/api/search', { method: 'POST', headers: headers, body: JSON.stringify({ query: query }) })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderResults(envelope.data);
          }
        })
        .catch(function () {
          renderResults([]);
        });
  }

  document.addEventListener('DOMContentLoaded', function () {
    var trigger = qs('searchTrigger');
    var closeBtn = qs('searchCloseBtn');
    var backdrop = qs('searchBackdrop');
    var input = qs('searchInput');
    if (!trigger || !backdrop) {
      return;
    }

    trigger.addEventListener('click', openModal);
    closeBtn.addEventListener('click', closeModal);
    backdrop.addEventListener('click', function (event) {
      if (event.target === backdrop) {
        closeModal();
      }
    });

    input.addEventListener('input', function () {
      clearTimeout(debounceTimer);
      var query = input.value;
      debounceTimer = setTimeout(function () {
        runSearch(query);
      }, 150);
    });

    input.addEventListener('keydown', function (event) {
      if (event.key === 'ArrowDown') {
        event.preventDefault();
        setActive(activeIndex + 1);
      } else if (event.key === 'ArrowUp') {
        event.preventDefault();
        setActive(activeIndex - 1);
      } else if (event.key === 'Enter') {
        event.preventDefault();
        activateResult(activeIndex);
      } else if (event.key === 'Escape') {
        closeModal();
      }
    });

    document.addEventListener('keydown', function (event) {
      var isModK = (event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k';
      if (isModK) {
        event.preventDefault();
        if (isModalOpen()) {
          closeModal();
        } else {
          openModal();
        }
      } else if (event.key === 'Escape' && isModalOpen()) {
        closeModal();
      }
    });
  });
})();
