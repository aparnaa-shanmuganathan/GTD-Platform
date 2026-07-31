/*
 * Attack Workspace client-side wiring (Phase 8).
 *
 * Single-view page, no tabs/drill-down - the same one-render-pipeline
 * pattern executive.js established (renderDashboard called both for the
 * server-embedded INITIAL_DASHBOARD on first paint and every filter-driven
 * re-fetch).
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

  var LINE_PALETTE_VARS = ['--blue', '--brass', '--red', '--green', '--warn'];

  function themeColors() {
    var cs = getComputedStyle(document.documentElement);
    var colors = {
      text: cs.getPropertyValue('--text').trim(),
      textDim: cs.getPropertyValue('--text-dim').trim(),
      textFaint: cs.getPropertyValue('--text-faint').trim(),
      border: cs.getPropertyValue('--border').trim(),
      surface: cs.getPropertyValue('--surface').trim(),
      blue: cs.getPropertyValue('--blue').trim(),
      blueSoft: cs.getPropertyValue('--blue-soft').trim()
    };
    colors.palette = LINE_PALETTE_VARS.map(function (name) {
      return cs.getPropertyValue(name).trim();
    });
    return colors;
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
     KPI ROW (CAC-AN-001, computeExecutiveKpis reused unmodified)
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
    qs('attackKpiZone').innerHTML = html;
  }

  /* =====================================================================
     RANKING (CAC-AN-002)
     ===================================================================== */
  function renderRanking(ranking) {
    var entries = (ranking && ranking.entries) || [];
    if (entries.length === 0) {
      qs('attackTypeRanking').innerHTML = '<p class="insight-empty">No data available for the selected filters.</p>';
      return;
    }
    var maxValue = Math.max.apply(null, entries.map(function (e) {
      return Number(e.value);
    }));
    var html = entries.map(function (e) {
      var barPct = maxValue > 0 ? (Number(e.value) / maxValue * 100).toFixed(1) : '0';
      var sharePct = e.shareOfTotal != null ? (Number(e.shareOfTotal) * 100).toFixed(1) + '%' : '';
      return '<div class="rank-row">'
          + '<div class="rank-num">' + String(e.rank).padStart(2, '0') + '</div>'
          + '<div class="rank-item-wrap"><div class="rank-name">' + escapeHtml(e.label) + '</div></div>'
          + '<div class="rank-value">' + fmt(e.value) + '</div>'
          + '<div class="rank-share">' + sharePct + '</div>'
          + '<div class="rank-bar-track"><div class="rank-bar-fill" style="width:' + barPct + '%"></div></div>'
          + '</div>';
    }).join('');
    qs('attackTypeRanking').innerHTML = html;
  }

  /* =====================================================================
     CHARTS
     ===================================================================== */
  function getOrCreateChart(elementId) {
    if (!chartInstances[elementId]) {
      chartInstances[elementId] = echarts.init(qs(elementId), null, { renderer: 'canvas' });
    }
    return chartInstances[elementId];
  }

  function renderLineChart(elementId, chart) {
    var tc = themeColors();
    var inst = getOrCreateChart(elementId);
    var data = (chart && chart.series && chart.series[0]) ? chart.series[0].data : [];
    inst.setOption({
      tooltip: { trigger: 'axis' },
      grid: { left: 44, right: 16, top: 16, bottom: 30 },
      xAxis: {
        type: 'category',
        data: (chart && chart.categories) || [],
        axisLabel: { color: tc.textFaint, fontSize: 10 },
        axisLine: { lineStyle: { color: tc.border } }
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: tc.textFaint, fontSize: 10 },
        splitLine: { lineStyle: { color: tc.border } }
      },
      series: [{
        type: 'line',
        data: data,
        smooth: true,
        showSymbol: false,
        lineStyle: { color: tc.blue, width: 2 },
        itemStyle: { color: tc.blue },
        areaStyle: { color: tc.blueSoft }
      }]
    }, true);
  }

  // Multi-series variant (one line per attack type) - the "prevalence over time" chart is the
  // one place this workspace needs more than one line, so this stays local rather than
  // becoming a second shared utility only one caller would ever use.
  function renderMultiLineChart(elementId, chart) {
    var tc = themeColors();
    var inst = getOrCreateChart(elementId);
    var series = ((chart && chart.series) || []).map(function (s, i) {
      var color = tc.palette[i % tc.palette.length];
      return {
        name: s.name,
        type: 'line',
        data: s.data,
        smooth: true,
        showSymbol: false,
        connectNulls: true,
        lineStyle: { color: color, width: 2 },
        itemStyle: { color: color }
      };
    });
    inst.setOption({
      tooltip: { trigger: 'axis' },
      legend: {
        top: 0, textStyle: { color: tc.textDim, fontSize: 10.5 }, itemWidth: 9, itemHeight: 9
      },
      grid: { left: 44, right: 16, top: 36, bottom: 30 },
      xAxis: {
        type: 'category',
        data: (chart && chart.categories) || [],
        axisLabel: { color: tc.textFaint, fontSize: 10 },
        axisLine: { lineStyle: { color: tc.border } }
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: tc.textFaint, fontSize: 10 },
        splitLine: { lineStyle: { color: tc.border } }
      },
      series: series
    }, true);
  }

  function renderCharts(dashboard) {
    renderMultiLineChart('prevalenceChartWrap', dashboard.prevalenceChart);
    renderLineChart('successRateChartWrap', dashboard.successRateChart);
  }

  function renderDashboard(data) {
    lastDashboard = data;
    renderKpis(data.kpis);
    renderRanking(data.ranking);
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
    fetch('/api/attack/dashboard', {
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
