/*
 * Audit & Activity Log client-side wiring (Phase 10) - filterable,
 * paginated, read-only view over audit_log. No edit/delete UI anywhere in
 * this file, matching audit_log's DB-trigger-enforced immutability.
 */
(function () {
  'use strict';

  var currentPage = 0;
  var pageSize = 50;
  var lastTotalCount = 0;

  function qs(id) {
    return document.getElementById(id);
  }

  function csrfToken() {
    return document.querySelector('meta[name="_csrf"]').content;
  }

  function csrfHeader() {
    return document.querySelector('meta[name="_csrf_header"]').content;
  }

  function jsonHeaders() {
    var headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader()] = csrfToken();
    return headers;
  }

  function escapeHtml(value) {
    if (value == null) {
      return '';
    }
    return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function showToast(title, body) {
    var stack = qs('toastStack');
    var el = document.createElement('div');
    el.className = 'alert alert-danger';
    el.style.boxShadow = 'var(--shadow-lg)';
    el.innerHTML = '<div><div class="alert-title">' + escapeHtml(title) + '</div>'
        + '<div class="alert-body">' + escapeHtml(body) + '</div></div>';
    stack.appendChild(el);
    setTimeout(function () {
      el.remove();
    }, 6000);
  }

  function renderEntries(data) {
    lastTotalCount = data.totalCount || 0;
    var entries = data.entries || [];
    if (entries.length === 0) {
      qs('auditEntries').innerHTML = '<p class="insight-empty">No matching audit entries.</p>';
    } else {
      var html = '<div class="table-wrap"><table class="table"><thead><tr>'
          + '<th>Occurred at</th><th>Actor</th><th>Action</th><th>Entity</th><th>Detail</th><th>IP</th>'
          + '</tr></thead><tbody>'
          + entries.map(function (e) {
            return '<tr>'
                + '<td>' + (e.occurredAt ? new Date(e.occurredAt).toLocaleString() : '—') + '</td>'
                + '<td>' + escapeHtml(e.actorUsername || 'system') + '</td>'
                + '<td>' + escapeHtml(e.actionType) + '</td>'
                + '<td>' + escapeHtml(e.entityType ? (e.entityType + (e.entityId ? ' #' + e.entityId : '')) : '—') + '</td>'
                + '<td>' + escapeHtml(e.details || '—') + '</td>'
                + '<td>' + escapeHtml(e.ipAddress || '—') + '</td>'
                + '</tr>';
          }).join('')
          + '</tbody></table></div>';
      qs('auditEntries').innerHTML = html;
    }

    var start = entries.length === 0 ? 0 : (data.page * data.pageSize) + 1;
    var end = (data.page * data.pageSize) + entries.length;
    qs('pageInfo').textContent = lastTotalCount === 0 ? 'No entries' : (start + '–' + end + ' of ' + lastTotalCount);
    qs('prevPageBtn').disabled = data.page <= 0;
    qs('nextPageBtn').disabled = end >= lastTotalCount;
  }

  function buildFilterRequest() {
    return {
      actor: qs('filterActor').value || null,
      actionType: qs('filterActionType').value || null,
      startDate: qs('filterStartDate').value || null,
      endDate: qs('filterEndDate').value || null,
      page: currentPage,
      pageSize: pageSize
    };
  }

  function applyFilters() {
    var bar = qs('filterBar');
    bar.classList.add('is-loading');
    fetch('/api/audit/entries', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(buildFilterRequest())
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderEntries(envelope.data);
          } else {
            showToast('Filter failed', (envelope.error && envelope.error.message) || 'Unknown error');
          }
        })
        .catch(function (err) {
          showToast('Filter failed', String(err));
        })
        .finally(function () {
          bar.classList.remove('is-loading');
        });
  }

  document.addEventListener('DOMContentLoaded', function () {
    renderEntries(window.INITIAL_ENTRIES || { entries: [], totalCount: 0, page: 0, pageSize: pageSize });

    ['filterActor', 'filterActionType', 'filterStartDate', 'filterEndDate'].forEach(function (id) {
      var el = qs(id);
      var eventName = el.type === 'date' ? 'change' : 'input';
      var timer = null;
      el.addEventListener(eventName, function () {
        clearTimeout(timer);
        timer = setTimeout(function () {
          currentPage = 0;
          applyFilters();
        }, 300);
      });
    });

    qs('clearFiltersBtn').addEventListener('click', function () {
      qs('filterActor').value = '';
      qs('filterActionType').value = '';
      qs('filterStartDate').value = '';
      qs('filterEndDate').value = '';
      currentPage = 0;
      applyFilters();
    });

    qs('prevPageBtn').addEventListener('click', function () {
      if (currentPage > 0) {
        currentPage -= 1;
        applyFilters();
      }
    });
    qs('nextPageBtn').addEventListener('click', function () {
      currentPage += 1;
      applyFilters();
    });

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
    });
  });
})();
