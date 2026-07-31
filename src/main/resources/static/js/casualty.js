/*
 * Casualty Workspace client-side wiring (Phase 8) - single-view, no
 * tabs/drill-down/ranking, same one-render-pipeline pattern as
 * attack.js/weapon.js/target.js.
 *
 * Apache ECharts is vendored locally (echarts.min.js, CLAUDE.md section 2).
 */
(function () {
  'use strict';

  var chartInstances = {};
  var lastDashboard = null;

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
    var num = Number(n);
    if (Number.isInteger(num)) {
      return num.toLocaleString('en-US');
    }
    return num.toLocaleString('en-US', { maximumFractionDigits: 2 });
  }

  // Ascending severity, left to right - UNKNOWN last since it isn't a severity level, just an
  // unclassified bucket (SeverityBandClassifier.SeverityBand's own declared order).
  var BAND_LABELS = { LOW: 'Low', MODERATE: 'Moderate', HIGH: 'High', CATASTROPHIC: 'Catastrophic', UNKNOWN: 'Unknown' };
  var BAND_COLOR_VARS = { LOW: '--green', MODERATE: '--warn', HIGH: '--red', CATASTROPHIC: '--red-dim', UNKNOWN: '--text-faint' };

  function themeColors() {
    var cs = getComputedStyle(document.documentElement);
    return {
      textDim: cs.getPropertyValue('--text-dim').trim(),
      textFaint: cs.getPropertyValue('--text-faint').trim(),
      border: cs.getPropertyValue('--border').trim(),
      surface: cs.getPropertyValue('--surface').trim()
    };
  }

  function bandColor(cs, bandKey) {
    return cs.getPropertyValue(BAND_COLOR_VARS[bandKey] || '--blue').trim();
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

  /* =====================================================================
     KPI ROW (CAC-AN-001) - computeExecutiveKpis's usual 6 plus Casualty
     Workspace's own Total Casualties / Avg fatalities / Avg injuries,
     already merged server-side into one flat list.
     ===================================================================== */
  function renderKpis(kpis) {
    var html = '<div class="kpi-grid">';
    (kpis || []).forEach(function (kpi) {
      var deltaHtml = '';
      if (kpi.trend) {
        var dirClass = kpi.trend.direction === 'UP' ? 'up' : (kpi.trend.direction === 'DOWN' ? 'down' : 'flat');
        deltaHtml = '<div class="kpi-delta ' + dirClass + '">' + escapeHtml(kpi.trend.deltaLabel) + '</div>';
      }
      html += '<div class="kpi-card">'
          + '<div class="kpi-top"><div class="kpi-label">' + escapeHtml(kpi.label) + '</div>'
          + '<div class="kpi-tag">' + escapeHtml(kpi.savId) + '</div></div>'
          + '<div class="kpi-value">' + fmt(kpi.value) + (kpi.unit ? escapeHtml(kpi.unit) : '') + '</div>'
          + deltaHtml
          + '</div>';
    });
    html += '</div>';
    qs('casualtyKpiZone').innerHTML = html;
  }

  /* =====================================================================
     SEVERITY BAND CHART (CAC-AN-003, single-series bar, one color per band)
     ===================================================================== */
  function getOrCreateChart(elementId) {
    if (!chartInstances[elementId]) {
      chartInstances[elementId] = echarts.init(qs(elementId), null, { renderer: 'canvas' });
    }
    return chartInstances[elementId];
  }

  function renderSeverityBandChart(elementId, chart) {
    var tc = themeColors();
    var cs = getComputedStyle(document.documentElement);
    var inst = getOrCreateChart(elementId);
    var bandKeys = (chart && chart.categories) || [];
    var data = ((chart && chart.series && chart.series[0]) ? chart.series[0].data : []).map(function (value, i) {
      return { value: value, itemStyle: { color: bandColor(cs, bandKeys[i]) } };
    });
    inst.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      grid: { left: 44, right: 16, top: 16, bottom: 30 },
      xAxis: {
        type: 'category',
        data: bandKeys.map(function (key) {
          return BAND_LABELS[key] || key;
        }),
        axisLabel: { color: tc.textFaint, fontSize: 10.5 },
        axisLine: { lineStyle: { color: tc.border } }
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: tc.textFaint, fontSize: 10 },
        splitLine: { lineStyle: { color: tc.border } }
      },
      series: [{ type: 'bar', data: data }]
    }, true);
  }

  function renderCharts(dashboard) {
    renderSeverityBandChart('severityBandChartWrap', dashboard.severityBandChart);
  }

  function renderDashboard(data) {
    lastDashboard = data;
    renderKpis(data.kpis);
    renderCharts(data);
  }

  /* =====================================================================
     FILTER BAR
     ===================================================================== */
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

  function applyFilters() {
    var bar = qs('filterBar');
    bar.classList.add('is-loading');
    fetch('/api/casualty/dashboard', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(buildFilterRequest())
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderDashboard(envelope.data);
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
    renderDashboard(window.INITIAL_DASHBOARD || {});

    qs('filterDate').addEventListener('change', applyFilters);
    qs('filterRegion').addEventListener('change', applyFilters);
    qs('filterAttack').addEventListener('change', applyFilters);

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
      if (lastDashboard) {
        renderCharts(lastDashboard);
      }
    });

    window.addEventListener('resize', function () {
      Object.keys(chartInstances).forEach(function (id) {
        chartInstances[id].resize();
      });
    });
  });
})();
