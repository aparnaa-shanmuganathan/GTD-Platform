/*
 * System Health Dashboard client-side wiring (Phase 10) - renders the
 * real, freshly-computed checks HealthController returns (DB connection,
 * active dataset version, materialized-view freshness, offline
 * verification). Nothing here is a hardcoded status - every value comes
 * straight from the server's own check.
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

  function badge(passed) {
    return '<span class="status-badge ' + (passed ? 'generated' : 'failed') + '">' + (passed ? 'PASS' : 'FAIL') + '</span>';
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

  function renderStatusCards(health) {
    var db = health.database || {};
    var dv = health.activeDatasetVersion;
    var offline = health.offlineVerification || {};

    var html = '<div class="kpi-grid" style="grid-template-columns:repeat(3,1fr);">';
    html += '<div class="kpi-card"><div class="kpi-top"><div class="kpi-label">Database connection</div>'
        + '<div class="kpi-tag">HEALTH</div></div>'
        + '<div class="kpi-value">' + badge(db.connected) + '</div>'
        + '<div class="kpi-delta flat">' + fmt(db.responseTimeMs) + ' ms response time</div></div>';

    html += '<div class="kpi-card"><div class="kpi-top"><div class="kpi-label">Active dataset version</div>'
        + '<div class="kpi-tag">HEALTH</div></div>'
        + '<div class="kpi-value" style="font-size:16px;">' + (dv ? escapeHtml(dv.versionLabel) : 'None active') + '</div>'
        + '<div class="kpi-delta flat">' + (dv && dv.rowCount != null ? fmt(dv.rowCount) + ' rows' : '—') + '</div></div>';

    html += '<div class="kpi-card"><div class="kpi-top"><div class="kpi-label">Offline verification</div>'
        + '<div class="kpi-tag">HEALTH</div></div>'
        + '<div class="kpi-value">' + badge(offline.passed) + '</div>'
        + '<div class="kpi-delta flat">' + fmt(offline.resourcesScanned) + ' assets scanned</div></div>';
    html += '</div>';

    qs('statusCards').innerHTML = html;
  }

  function renderMvFreshness(views) {
    if (!views || views.length === 0) {
      qs('mvFreshness').innerHTML = '<p class="insight-empty">No materialized view status available.</p>';
      return;
    }
    var html = '<div class="table-wrap"><table class="table"><thead><tr>'
        + '<th>View</th><th>Status</th><th>Last refreshed</th><th>Duration</th><th>Rows</th>'
        + '</tr></thead><tbody>'
        + views.map(function (v) {
          return '<tr>'
              + '<td>' + escapeHtml(v.viewName) + '</td>'
              + '<td>' + badge(v.healthy) + ' ' + escapeHtml(v.lastRefreshStatus) + '</td>'
              + '<td>' + (v.lastRefreshedAt ? new Date(v.lastRefreshedAt).toLocaleString() : 'never') + '</td>'
              + '<td>' + (v.lastRefreshDurationMs != null ? fmt(v.lastRefreshDurationMs) + ' ms' : '—') + '</td>'
              + '<td>' + fmt(v.rowCount) + '</td>'
              + '</tr>';
        }).join('')
        + '</tbody></table></div>';
    qs('mvFreshness').innerHTML = html;
  }

  function renderOfflineVerification(offline) {
    var html = '<p>' + badge(offline.passed) + ' &nbsp;'
        + (offline.passed
            ? 'No external (<code>http(s)://</code>) references found in ' + fmt(offline.resourcesScanned) + ' bundled templates/stylesheets.'
            : fmt((offline.findings || []).length) + ' external reference(s) found - this is a real violation of the zero-outbound-requests requirement.')
        + '</p>';
    if (offline.findings && offline.findings.length > 0) {
      html += '<div class="table-wrap" style="margin-top:10px;"><table class="table"><thead><tr>'
          + '<th>File</th><th>Line</th><th>URL</th></tr></thead><tbody>'
          + offline.findings.map(function (f) {
            return '<tr><td>' + escapeHtml(f.resourceName) + '</td><td>' + f.lineNumber + '</td><td>' + escapeHtml(f.url) + '</td></tr>';
          }).join('')
          + '</tbody></table></div>';
    }
    qs('offlineVerification').innerHTML = html;
  }

  function renderHealth(health) {
    renderStatusCards(health);
    renderMvFreshness(health.materializedViews);
    renderOfflineVerification(health.offlineVerification || {});
    qs('checkedAtHint').textContent = 'Last checked: ' + (health.checkedAt ? new Date(health.checkedAt).toLocaleString() : '—');
  }

  function runCheck() {
    var headers = { 'Content-Type': 'application/json' };
    headers[csrfHeader()] = csrfToken();
    qs('recheckBtn').disabled = true;
    fetch('/api/health/check', { method: 'POST', headers: headers })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderHealth(envelope.data);
          } else {
            showToast('Re-check failed', (envelope.error && envelope.error.message) || 'Unknown error');
          }
        })
        .catch(function (err) {
          showToast('Re-check failed', String(err));
        })
        .finally(function () {
          qs('recheckBtn').disabled = false;
        });
  }

  document.addEventListener('DOMContentLoaded', function () {
    renderHealth(window.INITIAL_HEALTH || {});
    qs('recheckBtn').addEventListener('click', runCheck);

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
    });
  });
})();
