/*
 * Report Workspace client-side wiring (Phase 6).
 *
 * The filter bar here scopes only the NEXT report to be generated (it is
 * not itself filtering the history table - past reports already carry their
 * own filter_snapshot, frozen at generation time). Generating a report
 * always re-fetches the full history from the server afterward rather than
 * locally prepending the new row, so the table can never drift from what
 * generated_report actually contains.
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

  function showToast(title, body, isError) {
    var stack = qs('toastStack');
    var el = document.createElement('div');
    el.className = isError === false ? 'alert alert-success' : 'alert alert-danger';
    el.style.boxShadow = 'var(--shadow-lg)';
    el.innerHTML = '<div><div class="alert-title">' + escapeHtml(title) + '</div>'
        + '<div class="alert-body">' + escapeHtml(body) + '</div></div>';
    stack.appendChild(el);
    setTimeout(function () {
      el.remove();
    }, 6000);
  }

  function buildFilterRequest() {
    var body = { regionIds: [], countryIds: [], attackTypeIds: [], weaponTypeIds: [], targetTypeIds: [], organizationIds: [] };

    var dateSel = qs('filterDate').value;
    if (window.MAX_YEAR != null) {
      var span = dateSel === 'last10' ? 9 : (dateSel === 'last5' ? 4 : null);
      if (span != null) {
        body.startDate = (window.MAX_YEAR - span) + '-01-01';
        body.endDate = window.MAX_YEAR + '-12-31';
      }
    }

    var region = qs('filterRegion').value;
    if (region) {
      body.regionIds = [parseInt(region, 10)];
    }
    var attack = qs('filterAttack').value;
    if (attack) {
      body.attackTypeIds = [parseInt(attack, 10)];
    }
    return body;
  }

  function renderHistory(reports) {
    var wrap = qs('historyTableWrap');
    var empty = qs('emptyState');
    if (!reports || reports.length === 0) {
      if (wrap) {
        wrap.style.display = 'none';
      }
      if (empty) {
        empty.style.display = 'flex';
      } else {
        insertEmptyState();
      }
      return;
    }
    if (empty) {
      empty.style.display = 'none';
    }
    if (!wrap) {
      return;
    }
    wrap.style.display = '';
    var body = qs('historyTableBody');
    body.innerHTML = reports.map(function (r) {
      var statusClass = r.status === 'GENERATED' ? 'generated' : 'failed';
      var downloadBtn = r.status === 'GENERATED'
          ? '<button class="btn btn-sm download-btn" data-report-id="' + r.id + '" data-report-name="'
              + escapeHtml(r.reportName) + '" data-report-format="' + r.format + '">Download</button>'
          : '';
      return '<tr>'
          + '<td>' + escapeHtml(r.reportName) + '</td>'
          + '<td>' + escapeHtml(r.format) + '</td>'
          + '<td>' + escapeHtml(r.datasetVersionLabel) + '</td>'
          + '<td>' + escapeHtml(formatTimestamp(r.generatedAt)) + '</td>'
          + '<td><span class="status-badge ' + statusClass + '">' + escapeHtml(r.status) + '</span></td>'
          + '<td>' + downloadBtn + '</td>'
          + '</tr>';
    }).join('');
  }

  function insertEmptyState() {
    var panel = qs('historyTableWrap') ? qs('historyTableWrap').parentElement : null;
    if (!panel) {
      return;
    }
    var div = document.createElement('div');
    div.id = 'emptyState';
    div.className = 'empty-state';
    div.innerHTML = '<h3>No reports yet</h3><p>Generate your first report above - it will show up here.</p>';
    panel.appendChild(div);
  }

  function formatTimestamp(iso) {
    if (!iso) {
      return '';
    }
    return iso.replace('T', ' ').substring(0, 16);
  }

  function refreshHistory() {
    return fetch('/api/report/history', {
      method: 'POST',
      headers: jsonHeaders(),
      body: '{}'
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderHistory(envelope.data);
          }
        });
  }

  function generateReport() {
    var btn = qs('generateReportBtn');
    btn.disabled = true;
    var body = {
      filter: buildFilterRequest(),
      format: qs('reportFormat').value,
      reportName: qs('reportNameInput').value || null,
      sourceWorkspace: 'Report Workspace'
    };

    fetch('/api/report/generate', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(body)
    })
        .then(function (resp) {
          return resp.json().then(function (envelope) {
            return { status: resp.status, envelope: envelope };
          });
        })
        .then(function (result) {
          if (result.envelope.success) {
            showToast('Report generated', result.envelope.data.reportName + ' is ready to download.', false);
            qs('reportNameInput').value = '';
            return refreshHistory();
          }
          showToast('Report generation failed', (result.envelope.error && result.envelope.error.message) || 'Unknown error');
          return refreshHistory();
        })
        .catch(function (err) {
          showToast('Report generation failed', String(err));
        })
        .finally(function () {
          btn.disabled = false;
        });
  }

  function downloadReport(reportId, reportName, format) {
    fetch('/api/report/download', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify({ reportId: parseInt(reportId, 10) })
    })
        .then(function (resp) {
          if (!resp.ok) {
            return resp.json().then(function (envelope) {
              throw new Error((envelope.error && envelope.error.message) || 'Download failed');
            });
          }
          return resp.blob();
        })
        .then(function (blob) {
          var extension = format === 'PDF' ? 'pdf' : 'csv';
          var url = URL.createObjectURL(blob);
          var a = document.createElement('a');
          a.href = url;
          a.download = reportName.replace(/[^a-zA-Z0-9-_ ]/g, '_') + '.' + extension;
          document.body.appendChild(a);
          a.click();
          a.remove();
          URL.revokeObjectURL(url);
        })
        .catch(function (err) {
          showToast('Download failed', String(err));
        });
  }

  document.addEventListener('DOMContentLoaded', function () {
    qs('generateReportBtn').addEventListener('click', generateReport);

    document.addEventListener('click', function (e) {
      var btn = e.target.closest('.download-btn');
      if (btn) {
        downloadReport(btn.dataset.reportId, btn.dataset.reportName, btn.dataset.reportFormat);
      }
    });

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
    });
  });
})();
