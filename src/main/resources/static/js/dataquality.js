/*
 * Data Quality Center client-side wiring (Phase 10) - batch list + a
 * rejection/duplicate breakdown drill-down. Purely read-only: every
 * endpoint this file calls is a POST but none of them change state
 * (CLAUDE.md section 12's access model amendment applies here at its most
 * literal - viewing this workspace, including drilling into a batch,
 * never requires authentication).
 */
(function () {
  'use strict';

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

  function fmt(n) {
    if (n == null) {
      return '—';
    }
    return Number(n).toLocaleString('en-US');
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

  function renderBatchList(batches) {
    if (!batches || batches.length === 0) {
      qs('batchList').innerHTML = '<p class="insight-empty">No import batches yet.</p>';
      return;
    }
    var html = '<div class="table-wrap"><table class="table"><thead><tr>'
        + '<th>File</th><th>Mode</th><th>Status</th><th>Staged</th><th>Rejected</th><th></th>'
        + '</tr></thead><tbody>'
        + batches.map(function (b) {
          return '<tr class="rank-row-clickable" data-batch-id="' + b.id + '" style="cursor:pointer;">'
              + '<td>' + escapeHtml(b.fileName) + '</td>'
              + '<td>' + escapeHtml(b.importMode) + '</td>'
              + '<td>' + escapeHtml(b.status) + '</td>'
              + '<td>' + fmt(b.rowsStaged) + '</td>'
              + '<td>' + fmt(b.rowsRejected) + '</td>'
              + '<td><button type="button" class="btn btn-sm" data-batch-id="' + b.id + '">Review</button></td>'
              + '</tr>';
        }).join('')
        + '</tbody></table></div>';
    qs('batchList').innerHTML = html;

    Array.prototype.forEach.call(qs('batchList').querySelectorAll('[data-batch-id]'), function (el) {
      el.addEventListener('click', function () {
        loadBatchDetail(parseInt(el.getAttribute('data-batch-id'), 10));
      });
    });
  }

  function renderBreakdown(breakdown) {
    if (!breakdown || breakdown.length === 0) {
      qs('rejectionBreakdown').innerHTML = '<p class="insight-empty">No rejected or duplicate-skipped rows in this batch.</p>';
      return;
    }
    var maxValue = Math.max.apply(null, breakdown.map(function (e) {
      return e.count;
    }));
    var html = breakdown.map(function (e, i) {
      var pct = maxValue > 0 ? (e.count / maxValue * 100).toFixed(1) : '0.0';
      return '<div class="rank-row">'
          + '<div class="rank-num">' + String(i + 1).padStart(2, '0') + '</div>'
          + '<div class="rank-item-wrap"><div class="rank-name">' + escapeHtml(e.rejectionReason) + '</div></div>'
          + '<div class="rank-value">' + fmt(e.count) + '</div>'
          + '<div class="rank-bar-track"><div class="rank-bar-fill" style="width:' + pct + '%"></div></div>'
          + '</div>';
    }).join('');
    qs('rejectionBreakdown').innerHTML = html;
  }

  function renderRejectedRows(rows, total) {
    if (!rows || rows.length === 0) {
      qs('rejectedRowsTable').innerHTML = '<p class="insight-empty">No rejected rows to show.</p>';
      return;
    }
    var html = '<p class="field-hint">' + fmt(total) + ' total rejected/skipped rows in this batch.</p>'
        + '<div class="table-wrap"><table class="table"><thead><tr>'
        + '<th>Event ID</th><th>Reason</th><th>Detail</th><th>Rejected at</th>'
        + '</tr></thead><tbody>'
        + rows.map(function (r) {
          return '<tr>'
              + '<td>' + escapeHtml(r.eventId || '—') + '</td>'
              + '<td>' + escapeHtml(r.rejectionReason) + '</td>'
              + '<td>' + escapeHtml(r.rejectionDetail || '—') + '</td>'
              + '<td>' + (r.rejectedAt ? new Date(r.rejectedAt).toLocaleString() : '—') + '</td>'
              + '</tr>';
        }).join('')
        + '</tbody></table></div>';
    qs('rejectedRowsTable').innerHTML = html;
  }

  function loadBatchDetail(batchId) {
    fetch('/api/dataquality/batch-detail', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ importBatchId: batchId })
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (!envelope.success) {
            showToast('Failed to load batch detail', (envelope.error && envelope.error.message) || 'Unknown error');
            return;
          }
          var data = envelope.data;
          qs('batchDetailTitle').textContent = 'Batch detail — ' + data.batch.fileName;
          renderBreakdown(data.breakdown);
          renderRejectedRows(data.rejectedRows, data.totalRejectedRows);
          qs('batchDetailPanel').style.display = '';
          qs('batchDetailPanel').scrollIntoView({ behavior: 'smooth', block: 'start' });
        })
        .catch(function (err) {
          showToast('Failed to load batch detail', String(err));
        });
  }

  document.addEventListener('DOMContentLoaded', function () {
    renderBatchList(window.INITIAL_BATCHES || []);

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
    });
  });
})();
