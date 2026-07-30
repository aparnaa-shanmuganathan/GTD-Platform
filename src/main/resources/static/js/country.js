/*
 * Country Workspace client-side wiring (Phase 5).
 *
 * Rankings / Compare / Country trend are three client-side-only tabs of this
 * one page - no URL/history state (CLAUDE.md section 12's confirmed
 * navigation-state decision for this phase). A browser back/refresh always
 * lands back on the default Rankings tab, never mid-comparison or
 * mid-drill-down, exactly like the Executive Workspace's filter bar never
 * reflects its state in the URL either.
 *
 * Apache ECharts is vendored locally (echarts.min.js, CLAUDE.md section 2).
 * Canvas rendering needs resolved color strings, not CSS custom-property
 * references - themeColors() mirrors executive.js's approach.
 */
(function () {
  'use strict';

  var chartInstances = {};
  var lastChartData = {};
  var selectedCountryId = null;
  var selectedCountryName = null;
  var lastCompareIds = [];

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

  function themeColors() {
    var cs = getComputedStyle(document.documentElement);
    return {
      textFaint: cs.getPropertyValue('--text-faint').trim(),
      border: cs.getPropertyValue('--border').trim(),
      surface: cs.getPropertyValue('--surface').trim(),
      blue: cs.getPropertyValue('--blue').trim(),
      blueSoft: cs.getPropertyValue('--blue-soft').trim()
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

  function errorMessage(envelope, fallback) {
    return (envelope && envelope.error && envelope.error.message) || fallback;
  }

  /* =====================================================================
     TABS
     ===================================================================== */
  var TABS = ['tabRanking', 'tabCompare', 'tabDrilldown'];
  var PANELS = { tabRanking: 'rankingPanel', tabCompare: 'comparePanel', tabDrilldown: 'drilldownPanel' };

  function selectTab(tabId) {
    if (qs(tabId).disabled) {
      return;
    }
    TABS.forEach(function (id) {
      var active = id === tabId;
      var tabEl = qs(id);
      tabEl.setAttribute('aria-selected', active ? 'true' : 'false');
      tabEl.tabIndex = active ? 0 : -1;
      qs(PANELS[id]).hidden = !active;
    });
    // Charts inside a panel that was `hidden` never got a real size to lay
    // out against - resize once it becomes visible so ECharts doesn't render
    // into a zero-height canvas.
    Object.keys(chartInstances).forEach(function (id) {
      chartInstances[id].resize();
    });
  }

  TABS.forEach(function (id) {
    qs(id).addEventListener('click', function () {
      selectTab(id);
    });
  });

  /* =====================================================================
     FILTER BAR (shared date/region/attack-type scope for every tab)
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

  /* =====================================================================
     RANKING (CAC-AN-002, reused from Phase 4 unmodified beyond entityId)
     ===================================================================== */
  function renderRanking(ranking) {
    var entries = (ranking && ranking.entries) || [];
    if (entries.length === 0) {
      qs('countryRanking').innerHTML = '<p class="insight-empty">No data available for the selected filters.</p>';
      return;
    }
    var maxValue = Math.max.apply(null, entries.map(function (e) {
      return Number(e.value);
    }));
    var html = entries.map(function (e) {
      var barPct = maxValue > 0 ? (Number(e.value) / maxValue * 100).toFixed(1) : '0';
      var sharePct = e.shareOfTotal != null ? (Number(e.shareOfTotal) * 100).toFixed(1) + '%' : '';
      return '<div class="rank-row is-clickable" data-country-id="' + e.entityId + '" data-country-name="'
          + escapeHtml(e.label) + '" tabindex="0" role="button">'
          + '<div class="rank-num">' + String(e.rank).padStart(2, '0') + '</div>'
          + '<div class="rank-item-wrap"><div class="rank-name">' + escapeHtml(e.label) + '</div></div>'
          + '<div class="rank-value">' + fmt(e.value) + '</div>'
          + '<div class="rank-share">' + sharePct + '</div>'
          + '<div class="rank-bar-track"><div class="rank-bar-fill" style="width:' + barPct + '%"></div></div>'
          + '</div>';
    }).join('');
    qs('countryRanking').innerHTML = html;
  }

  function fetchRanking() {
    var bar = qs('filterBar');
    bar.classList.add('is-loading');
    fetch('/api/country/ranking', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(buildFilterRequest())
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderRanking(envelope.data);
          } else {
            showToast('Filter failed', errorMessage(envelope, 'Unknown error'));
          }
        })
        .catch(function (err) {
          showToast('Filter failed', String(err));
        })
        .finally(function () {
          bar.classList.remove('is-loading');
        });
  }

  qs('countryRanking').addEventListener('click', function (e) {
    var row = e.target.closest('.rank-row');
    if (row) {
      drillDownInto(parseInt(row.dataset.countryId, 10), row.dataset.countryName);
    }
  });
  qs('countryRanking').addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.key !== ' ') {
      return;
    }
    var row = e.target.closest('.rank-row');
    if (row) {
      e.preventDefault();
      drillDownInto(parseInt(row.dataset.countryId, 10), row.dataset.countryName);
    }
  });

  /* =====================================================================
     COUNTRY TREND DRILL-DOWN (SVC-TREND parameterized by country, via
     SVC-COMPARE's single-country path - see CompareService's Javadoc)
     ===================================================================== */
  function getOrCreateChart(elementId) {
    if (!chartInstances[elementId]) {
      chartInstances[elementId] = echarts.init(qs(elementId), null, { renderer: 'canvas' });
    }
    return chartInstances[elementId];
  }

  // A new comparison can select an entirely different set of countries, so
  // the previous run's per-country chart DOM nodes are discarded when
  // compareGrid's markup is replaced. Disposing their ECharts instances (and
  // forgetting their cached data) before that happens avoids leaking
  // instances bound to removed nodes and stale data bleeding into the next
  // comparison - the same "no stale state on switching" concern as
  // drill-down replacing, not appending, its own chart on each new selection.
  function disposeCompareCharts() {
    Object.keys(chartInstances).forEach(function (id) {
      if (id.indexOf('compareChart-') === 0) {
        chartInstances[id].dispose();
        delete chartInstances[id];
        delete lastChartData[id];
      }
    });
  }

  function renderLineChart(elementId, chart) {
    lastChartData[elementId] = chart;
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

  var DRILLDOWN_KPI_IDS = ['kpiIncidents', 'kpiFatalities', 'kpiInjuries', 'kpiSuccess'];

  function renderDrilldownKpis(kpis) {
    var byId = {};
    (kpis || []).forEach(function (k) {
      byId[k.id] = k;
    });
    var html = '<div class="kpi-grid" style="grid-template-columns:repeat(4,1fr);">';
    DRILLDOWN_KPI_IDS.forEach(function (id) {
      var kpi = byId[id];
      if (!kpi) {
        return;
      }
      html += '<div class="kpi-card"><div class="kpi-top"><div class="kpi-label">' + escapeHtml(kpi.label) + '</div>'
          + '<div class="kpi-tag">' + escapeHtml(kpi.savId) + '</div></div>'
          + '<div class="kpi-value">' + fmt(kpi.value) + (kpi.unit ? escapeHtml(kpi.unit) : '') + '</div></div>';
    });
    html += '</div>';
    qs('drilldownKpiZone').innerHTML = html;
  }

  function drillDownInto(countryId, countryName) {
    selectedCountryId = countryId;
    selectedCountryName = countryName;
    qs('tabDrilldown').disabled = false;
    qs('drilldownTitle').textContent = countryName + ' — incidents by year';
    selectTab('tabDrilldown');
    fetchDrilldown();
  }

  function fetchDrilldown() {
    if (selectedCountryId == null) {
      return;
    }
    var body = buildFilterRequest();
    body.countryIds = [selectedCountryId];
    fetch('/api/country/drilldown', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(body)
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderDrilldownKpis(envelope.data.kpis);
            renderLineChart('drilldownChartWrap', envelope.data.trendChart);
          } else {
            showToast('Drill-down failed', errorMessage(envelope, 'Unknown error'));
          }
        })
        .catch(function (err) {
          showToast('Drill-down failed', String(err));
        });
  }

  /* =====================================================================
     COMPARE (SVC-COMPARE, 2-4 countries side by side)
     ===================================================================== */
  var MAX_COMPARE = 4;

  function checkedCompareBoxes() {
    return Array.from(document.querySelectorAll('.compare-checkbox:checked'));
  }

  function updateComparePickerState() {
    var checked = checkedCompareBoxes();
    var atLimit = checked.length >= MAX_COMPARE;
    document.querySelectorAll('.compare-checkbox').forEach(function (box) {
      box.disabled = atLimit && !box.checked;
    });
    qs('runCompareBtn').disabled = checked.length < 2 || checked.length > MAX_COMPARE;
  }

  document.querySelectorAll('.compare-checkbox').forEach(function (box) {
    box.addEventListener('change', updateComparePickerState);
  });

  function renderCompareCard(entry) {
    var chartId = 'compareChart-' + entry.countryId;
    var byId = {};
    (entry.kpis || []).forEach(function (k) {
      byId[k.id] = k;
    });
    var miniHtml = ['kpiIncidents', 'kpiFatalities', 'kpiInjuries', 'kpiSuccess'].map(function (id) {
      var kpi = byId[id];
      if (!kpi) {
        return '';
      }
      return '<div class="compare-kpi-mini"><div class="compare-kpi-mini-label">' + escapeHtml(kpi.label) + '</div>'
          + '<div class="compare-kpi-mini-value">' + fmt(kpi.value) + (kpi.unit ? escapeHtml(kpi.unit) : '') + '</div></div>';
    }).join('');

    return '<div class="compare-card">'
        + '<div class="compare-card-head"><div class="compare-card-title">' + escapeHtml(entry.countryName) + '</div></div>'
        + '<div class="compare-kpi-mini-grid">' + miniHtml + '</div>'
        + '<div class="compare-chart-wrap" id="' + chartId + '"></div>'
        + '</div>';
  }

  function fetchCompare() {
    var checked = checkedCompareBoxes();
    if (checked.length < 2 || checked.length > MAX_COMPARE) {
      return;
    }
    var ids = checked.map(function (box) {
      return parseInt(box.value, 10);
    });
    lastCompareIds = ids;

    var body = buildFilterRequest();
    body.countryIds = ids;

    qs('runCompareBtn').disabled = true;
    fetch('/api/country/compare', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(body)
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (!envelope.success) {
            showToast('Compare failed', errorMessage(envelope, 'Unknown error'));
            return;
          }
          var entries = envelope.data || [];
          disposeCompareCharts();
          qs('compareGrid').innerHTML = entries.map(renderCompareCard).join('');
          entries.forEach(function (entry) {
            renderLineChart('compareChart-' + entry.countryId, entry.trendChart);
          });
        })
        .catch(function (err) {
          showToast('Compare failed', String(err));
        })
        .finally(function () {
          updateComparePickerState();
        });
  }

  qs('runCompareBtn').addEventListener('click', fetchCompare);

  /* =====================================================================
     WIRING
     ===================================================================== */
  document.addEventListener('DOMContentLoaded', function () {
    renderRanking(window.INITIAL_RANKING || {});

    qs('filterDate').addEventListener('change', onFilterChanged);
    qs('filterRegion').addEventListener('change', onFilterChanged);
    qs('filterAttack').addEventListener('change', onFilterChanged);

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
      Object.keys(lastChartData).forEach(function (id) {
        renderLineChart(id, lastChartData[id]);
      });
    });

    window.addEventListener('resize', function () {
      Object.keys(chartInstances).forEach(function (id) {
        chartInstances[id].resize();
      });
    });
  });

  // The filter bar is shared across all three tabs: a date/region/attack-type
  // change re-fetches the ranking (always visible/relevant) plus whichever of
  // compare/drill-down already has a selection, so the currently open tab
  // never shows stale data after the filter changes underneath it.
  function onFilterChanged() {
    fetchRanking();
    if (selectedCountryId != null) {
      fetchDrilldown();
    }
    if (lastCompareIds.length >= 2) {
      fetchCompare();
    }
  }
})();
