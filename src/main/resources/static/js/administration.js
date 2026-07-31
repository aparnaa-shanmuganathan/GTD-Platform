/*
 * Administration Workspace client-side wiring (Phase 10) - CONFIG form,
 * import upload/status/confirm flow (reusing the existing, already-
 * authenticated /api/import/** endpoints unchanged - this file is only a
 * new UI client of them), and a manual materialized-view refresh action.
 *
 * Import-completion notification: purely client-side, driven by this
 * page's own status polling and gated by CONFIG's notifyOnImportCompletion
 * flag - there is no server-push/email/webhook mechanism anywhere in this
 * platform (confirmed with the user before implementation).
 */
(function () {
  'use strict';

  var pollTimer = null;
  var currentBatchId = null;
  var lastNotifiedStatus = null;

  function qs(id) {
    return document.getElementById(id);
  }

  function csrfToken() {
    return document.querySelector('meta[name="_csrf"]').content;
  }

  function csrfHeader() {
    return document.querySelector('meta[name="_csrf_header"]').content;
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

  function showToast(title, body, isError) {
    var stack = qs('toastStack');
    var el = document.createElement('div');
    el.className = isError ? 'alert alert-danger' : 'alert';
    if (!isError) {
      el.style.borderColor = 'var(--green-dim)';
      el.style.background = 'var(--green-soft)';
      el.style.color = 'var(--green)';
    }
    el.style.boxShadow = 'var(--shadow-lg)';
    el.innerHTML = '<div><div class="alert-title">' + escapeHtml(title) + '</div>'
        + '<div class="alert-body">' + escapeHtml(body) + '</div></div>';
    stack.appendChild(el);
    setTimeout(function () {
      el.remove();
    }, 6000);
  }

  /* =====================================================================
     CONFIG FORM
     ===================================================================== */
  function setupConfigForm() {
    var form = qs('configForm');
    if (!form || !window.IS_AUTHENTICATED) {
      return;
    }
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      var body = {
        sessionTimeoutMinutes: parseInt(qs('sessionTimeoutMinutes').value, 10),
        defaultImportMode: qs('defaultImportMode').value,
        materializedViewAutoRefresh: qs('materializedViewAutoRefresh').checked,
        notifyOnImportCompletion: qs('notifyOnImportCompletion').checked
      };
      var headers = { 'Content-Type': 'application/json' };
      headers[csrfHeader()] = csrfToken();

      fetch('/api/config/save', { method: 'POST', headers: headers, body: JSON.stringify(body) })
          .then(function (resp) {
            return resp.json();
          })
          .then(function (envelope) {
            if (envelope.success) {
              window.NOTIFY_ON_IMPORT_COMPLETION = envelope.data.notifyOnImportCompletion;
              var hint = qs('configSavedHint');
              hint.textContent = 'Settings saved at ' + new Date().toLocaleTimeString() + '.';
              hint.style.display = '';
              showToast('Settings saved', 'Platform configuration updated successfully.', false);
            } else {
              showToast('Save failed', (envelope.error && envelope.error.message) || 'Unknown error', true);
            }
          })
          .catch(function (err) {
            showToast('Save failed', String(err), true);
          });
    });
  }

  /* =====================================================================
     IMPORT UPLOAD / STATUS / CONFIRM
     ===================================================================== */
  function setupUploadForm() {
    var form = qs('uploadForm');
    if (!form) {
      return;
    }
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      var fileInput = qs('csvFile');
      if (!fileInput.files || fileInput.files.length === 0) {
        return;
      }
      var formData = new FormData();
      formData.append('file', fileInput.files[0]);
      formData.append('mode', qs('uploadMode').value);

      var headers = {};
      headers[csrfHeader()] = csrfToken();

      qs('uploadBtn').disabled = true;
      fetch('/api/import/upload', { method: 'POST', headers: headers, body: formData })
          .then(function (resp) {
            return resp.json();
          })
          .then(function (envelope) {
            if (envelope.success) {
              currentBatchId = envelope.data.importBatchId;
              lastNotifiedStatus = null;
              qs('importProgress').style.display = '';
              qs('confirmBtn').style.display = 'none';
              startPolling();
            } else {
              showToast('Upload failed', (envelope.error && envelope.error.message) || 'Unknown error', true);
            }
          })
          .catch(function (err) {
            showToast('Upload failed', String(err), true);
          })
          .finally(function () {
            qs('uploadBtn').disabled = false;
          });
    });

    qs('confirmBtn').addEventListener('click', function () {
      var headers = { 'Content-Type': 'application/json' };
      headers[csrfHeader()] = csrfToken();
      fetch('/api/import/confirm', {
        method: 'POST',
        headers: headers,
        body: JSON.stringify({ importBatchId: currentBatchId })
      })
          .then(function (resp) {
            return resp.json();
          })
          .then(function (envelope) {
            if (envelope.success) {
              showToast('Import confirmed', 'Dataset version ' + envelope.data.versionLabel + ' is now active.', false);
              qs('confirmBtn').style.display = 'none';
              loadBatchHistory();
            } else {
              showToast('Confirm failed', (envelope.error && envelope.error.message) || 'Unknown error', true);
            }
          })
          .catch(function (err) {
            showToast('Confirm failed', String(err), true);
          });
    });
  }

  function startPolling() {
    if (pollTimer) {
      clearInterval(pollTimer);
    }
    pollTimer = setInterval(pollStatus, 1500);
    pollStatus();
  }

  function pollStatus() {
    if (currentBatchId == null) {
      return;
    }
    var headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader()] = csrfToken();
    fetch('/api/import/status', {
      method: 'POST',
      headers: headers,
      body: JSON.stringify({ importBatchId: currentBatchId })
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (!envelope.success) {
            return;
          }
          var data = envelope.data;
          qs('importStatusLine').textContent = 'Batch ' + data.importBatchId + ': ' + data.status
              + ' (' + fmt(data.rowsStaged) + ' staged, ' + fmt(data.rowsRejected) + ' rejected)';

          if (data.status === 'AWAITING_CONFIRMATION') {
            qs('confirmBtn').style.display = '';
            clearInterval(pollTimer);
            notifyCompletionIfNeeded(data.status, 'Import ready to confirm', 'Batch ' + data.importBatchId + ' staged successfully and is awaiting confirmation.');
          } else if (data.status === 'FAILED' || data.status === 'COMPLETED' || data.status === 'CANCELLED') {
            clearInterval(pollTimer);
            notifyCompletionIfNeeded(data.status, 'Import ' + data.status.toLowerCase(), 'Batch ' + data.importBatchId + ' finished with status ' + data.status + '.');
            loadBatchHistory();
          }
        })
        .catch(function () {
          clearInterval(pollTimer);
        });
  }

  function notifyCompletionIfNeeded(status, title, body) {
    if (status === lastNotifiedStatus) {
      return;
    }
    lastNotifiedStatus = status;
    if (window.NOTIFY_ON_IMPORT_COMPLETION) {
      showToast(title, body, status === 'FAILED');
    }
  }

  /* =====================================================================
     MANUAL MATERIALIZED VIEW REFRESH
     ===================================================================== */
  function setupRefreshViewsButton() {
    var btn = qs('refreshViewsBtn');
    if (!btn) {
      return;
    }
    btn.addEventListener('click', function () {
      var headers = {};
      headers[csrfHeader()] = csrfToken();
      btn.disabled = true;
      fetch('/api/administration/refresh-views', { method: 'POST', headers: headers })
          .then(function (resp) {
            return resp.json();
          })
          .then(function (envelope) {
            if (envelope.success) {
              showToast('Views refreshed', 'All materialized views were refreshed successfully.', false);
            } else {
              showToast('Refresh failed', (envelope.error && envelope.error.message) || 'Unknown error', true);
            }
          })
          .catch(function (err) {
            showToast('Refresh failed', String(err), true);
          })
          .finally(function () {
            btn.disabled = false;
          });
    });
  }

  /* =====================================================================
     BATCH HISTORY
     ===================================================================== */
  function renderBatchHistory(batches) {
    if (!batches || batches.length === 0) {
      qs('batchHistory').innerHTML = '<p class="insight-empty">No import batches yet.</p>';
      return;
    }
    var html = '<div class="table-wrap"><table class="table"><thead><tr>'
        + '<th>File</th><th>Mode</th><th>Status</th><th>Staged</th><th>Rejected</th><th>Started</th>'
        + '</tr></thead><tbody>'
        + batches.map(function (b) {
          return '<tr>'
              + '<td>' + escapeHtml(b.fileName) + '</td>'
              + '<td>' + escapeHtml(b.importMode) + '</td>'
              + '<td>' + escapeHtml(b.status) + '</td>'
              + '<td>' + fmt(b.rowsStaged) + '</td>'
              + '<td>' + fmt(b.rowsRejected) + '</td>'
              + '<td>' + (b.startedAt ? new Date(b.startedAt).toLocaleString() : '—') + '</td>'
              + '</tr>';
        }).join('')
        + '</tbody></table></div>';
    qs('batchHistory').innerHTML = html;
  }

  function loadBatchHistory() {
    fetch('/api/administration/import-batches', { method: 'POST', headers: { 'Content-Type': 'application/json' } })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderBatchHistory(envelope.data);
          }
        });
  }

  document.addEventListener('DOMContentLoaded', function () {
    renderBatchHistory(window.INITIAL_BATCHES || []);
    setupConfigForm();
    setupUploadForm();
    setupRefreshViewsButton();

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
    });
  });
})();
