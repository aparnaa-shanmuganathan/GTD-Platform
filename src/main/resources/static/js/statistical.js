/*
 * Statistical Workspace client-side wiring (Phase 9) - single-view, no
 * tabs/drill-down, same one-render-pipeline pattern as attack.js/weapon.js/
 * target.js/casualty.js.
 *
 * Apache ECharts is vendored locally (echarts.min.js, CLAUDE.md section 2).
 */
(function () {
  'use strict';

  var chartInstances = {};
  var lastSummary = null;

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

  function fmt(n, decimals) {
    if (n == null) {
      return '—';
    }
    var num = Number(n);
    if (decimals != null) {
      return num.toLocaleString('en-US', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
    }
    if (Number.isInteger(num)) {
      return num.toLocaleString('en-US');
    }
    return num.toLocaleString('en-US', { maximumFractionDigits: 2 });
  }

  function themeColors() {
    var cs = getComputedStyle(document.documentElement);
    return {
      text: cs.getPropertyValue('--text').trim(),
      textDim: cs.getPropertyValue('--text-dim').trim(),
      textFaint: cs.getPropertyValue('--text-faint').trim(),
      border: cs.getPropertyValue('--border').trim(),
      surface: cs.getPropertyValue('--surface').trim(),
      blue: cs.getPropertyValue('--blue').trim(),
      blueSoft: cs.getPropertyValue('--blue-soft').trim(),
      brass: cs.getPropertyValue('--brass').trim(),
      red: cs.getPropertyValue('--red').trim()
    };
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
     KEY STATISTICS (CAC-AN-006) - Trend Slope + Correlation mini-cards,
     reusing the .compare-kpi-mini component Country Workspace's compare
     feature already established.
     ===================================================================== */
  function renderStatMiniGrid(summary) {
    var slopeText = summary.trendSlope != null ? fmt(summary.trendSlope, 2) + ' / yr' : 'Not enough data';
    var correlationText = summary.correlationCoefficient != null ? fmt(summary.correlationCoefficient, 3) : 'Not enough data';

    var html = ''
        + '<div class="compare-kpi-mini"><div class="compare-kpi-mini-label">Trend slope (incidents/year)</div>'
        + '<div class="compare-kpi-mini-value">' + escapeHtml(slopeText) + '</div></div>'
        + '<div class="compare-kpi-mini"><div class="compare-kpi-mini-label">' + escapeHtml(summary.correlationDescription || 'Correlation') + '</div>'
        + '<div class="compare-kpi-mini-value">' + escapeHtml(correlationText) + '</div></div>';
    qs('statMiniGrid').innerHTML = html;

    var outlierYears = summary.outlierYears || [];
    qs('outlierYearsHint').textContent = outlierYears.length === 0
        ? 'No outlier years detected (Z-score method, |z| > 2.0) for the selected filters.'
        : 'Outlier years (Z-score method, |z| > 2.0): ' + outlierYears.join(', ') + '.';
  }

  /* =====================================================================
     TREND + MOVING AVERAGE CHART, with outlier years marked
     ===================================================================== */
  function getOrCreateChart(elementId) {
    if (!chartInstances[elementId]) {
      chartInstances[elementId] = echarts.init(qs(elementId), null, { renderer: 'canvas' });
    }
    return chartInstances[elementId];
  }

  function renderTrendChart(elementId, chart, outlierYears) {
    var tc = themeColors();
    var inst = getOrCreateChart(elementId);
    var categories = (chart && chart.categories) || [];
    var series = (chart && chart.series) || [];
    var incidentsSeries = series[0] || { data: [] };
    var movingAverageSeries = series[1] || { data: [] };

    var outlierPoints = (outlierYears || [])
        .map(function (year) {
          var idx = categories.indexOf(String(year));
          if (idx === -1) {
            return null;
          }
          return { name: 'Outlier', coord: [idx, incidentsSeries.data[idx]] };
        })
        .filter(function (point) {
          return point != null;
        });

    inst.setOption({
      tooltip: { trigger: 'axis' },
      legend: {
        top: 0, textStyle: { color: tc.textDim, fontSize: 10.5 }, itemWidth: 9, itemHeight: 9
      },
      grid: { left: 44, right: 16, top: 36, bottom: 30 },
      xAxis: {
        type: 'category',
        data: categories,
        axisLabel: { color: tc.textFaint, fontSize: 10 },
        axisLine: { lineStyle: { color: tc.border } }
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: tc.textFaint, fontSize: 10 },
        splitLine: { lineStyle: { color: tc.border } }
      },
      series: [
        {
          name: 'Incidents',
          type: 'line',
          data: incidentsSeries.data,
          smooth: true,
          showSymbol: false,
          lineStyle: { color: tc.blue, width: 2 },
          itemStyle: { color: tc.blue },
          areaStyle: { color: tc.blueSoft },
          markPoint: {
            symbol: 'pin',
            symbolSize: 34,
            itemStyle: { color: tc.red },
            data: outlierPoints
          }
        },
        {
          name: 'Moving average',
          type: 'line',
          data: movingAverageSeries.data,
          smooth: true,
          showSymbol: false,
          lineStyle: { color: tc.brass, width: 2, type: 'dashed' },
          itemStyle: { color: tc.brass }
        }
      ]
    }, true);
  }

  /* =====================================================================
     PERCENTILE RANKING (CAC-AN-006)
     ===================================================================== */
  function renderPercentileRanking(entries) {
    if (!entries || entries.length === 0) {
      qs('percentileRanking').innerHTML = '<p class="insight-empty">No data available for the selected filters.</p>';
      return;
    }
    var html = entries.map(function (e, i) {
      return '<div class="rank-row">'
          + '<div class="rank-num">' + String(i + 1).padStart(2, '0') + '</div>'
          + '<div class="rank-item-wrap"><div class="rank-name">' + escapeHtml(e.label) + '</div></div>'
          + '<div class="rank-value">' + fmt(e.value) + '</div>'
          + '<div class="rank-share">' + fmt(e.percentile, 1) + ' pct' + '</div>'
          + '<div class="rank-bar-track"><div class="rank-bar-fill" style="width:' + Number(e.percentile).toFixed(1) + '%"></div></div>'
          + '</div>';
    }).join('');
    qs('percentileRanking').innerHTML = html;
  }

  function renderSummary(summary) {
    lastSummary = summary;
    renderStatMiniGrid(summary);
    renderTrendChart('trendChartWrap', summary.trendWithMovingAverage, summary.outlierYears);
    renderPercentileRanking(summary.percentileRanking);
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
    fetch('/api/statistical/summary', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(buildFilterRequest())
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderSummary(envelope.data);
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
    renderSummary(window.INITIAL_SUMMARY || {});

    qs('filterDate').addEventListener('change', applyFilters);
    qs('filterRegion').addEventListener('change', applyFilters);
    qs('filterAttack').addEventListener('change', applyFilters);

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
      if (lastSummary) {
        renderTrendChart('trendChartWrap', lastSummary.trendWithMovingAverage, lastSummary.outlierYears);
      }
    });

    window.addEventListener('resize', function () {
      Object.keys(chartInstances).forEach(function (id) {
        chartInstances[id].resize();
      });
    });
  });
})();
