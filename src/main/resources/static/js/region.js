/*
 * Region Workspace client-side wiring (Phase 8).
 *
 * Rankings / Region trend are two client-side-only tabs of this one page -
 * no URL/history state, the same navigation-state decision Country
 * Workspace's filter bar established in Phase 5 (see country.js). No
 * Compare tab: the spec asks for regional rollups + member-country
 * breakdown, not region-to-region comparison.
 *
 * Apache ECharts is vendored locally (echarts.min.js, CLAUDE.md section 2).
 */
(function () {
  'use strict';

  var chartInstances = {};
  var lastChartData = {};
  var selectedRegionId = null;

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
  var TABS = ['tabRanking', 'tabDrilldown'];
  var PANELS = { tabRanking: 'rankingPanel', tabDrilldown: 'drilldownPanel' };

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

  /* =====================================================================
     RANKING (CAC-AN-002)
     ===================================================================== */
  function renderRankRows(containerId, entries, clickable, dataAttr) {
    if (entries.length === 0) {
      qs(containerId).innerHTML = '<p class="insight-empty">No data available for the selected filters.</p>';
      return;
    }
    var maxValue = Math.max.apply(null, entries.map(function (e) {
      return Number(e.value);
    }));
    var html = entries.map(function (e) {
      var barPct = maxValue > 0 ? (Number(e.value) / maxValue * 100).toFixed(1) : '0';
      var sharePct = e.shareOfTotal != null ? (Number(e.shareOfTotal) * 100).toFixed(1) + '%' : '';
      var rowAttrs = clickable
          ? ' class="rank-row is-clickable" data-' + dataAttr + '-id="' + e.entityId + '" data-' + dataAttr + '-name="'
              + escapeHtml(e.label) + '" tabindex="0" role="button"'
          : ' class="rank-row"';
      return '<div' + rowAttrs + '>'
          + '<div class="rank-num">' + String(e.rank).padStart(2, '0') + '</div>'
          + '<div class="rank-item-wrap"><div class="rank-name">' + escapeHtml(e.label) + '</div></div>'
          + '<div class="rank-value">' + fmt(e.value) + '</div>'
          + '<div class="rank-share">' + sharePct + '</div>'
          + '<div class="rank-bar-track"><div class="rank-bar-fill" style="width:' + barPct + '%"></div></div>'
          + '</div>';
    }).join('');
    qs(containerId).innerHTML = html;
  }

  function renderRanking(ranking) {
    renderRankRows('regionRanking', (ranking && ranking.entries) || [], true, 'region');
  }

  function fetchRanking() {
    var bar = qs('filterBar');
    bar.classList.add('is-loading');
    fetch('/api/region/ranking', {
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

  qs('regionRanking').addEventListener('click', function (e) {
    var row = e.target.closest('.rank-row');
    if (row && row.dataset.regionId) {
      drillDownInto(parseInt(row.dataset.regionId, 10), row.dataset.regionName);
    }
  });
  qs('regionRanking').addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.key !== ' ') {
      return;
    }
    var row = e.target.closest('.rank-row');
    if (row && row.dataset.regionId) {
      e.preventDefault();
      drillDownInto(parseInt(row.dataset.regionId, 10), row.dataset.regionName);
    }
  });

  /* =====================================================================
     REGION DRILL-DOWN (SVC-AGG/SVC-TREND parameterized by region, plus
     member-country ranking - RegionController orchestrates all three)
     ===================================================================== */
  function getOrCreateChart(elementId) {
    if (!chartInstances[elementId]) {
      chartInstances[elementId] = echarts.init(qs(elementId), null, { renderer: 'canvas' });
    }
    return chartInstances[elementId];
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

  function drillDownInto(regionId, regionName) {
    selectedRegionId = regionId;
    qs('tabDrilldown').disabled = false;
    qs('drilldownTitle').textContent = regionName + ' — incidents by year';
    selectTab('tabDrilldown');
    fetchDrilldown();
  }

  function fetchDrilldown() {
    if (selectedRegionId == null) {
      return;
    }
    var body = buildFilterRequest();
    body.regionIds = [selectedRegionId];
    fetch('/api/region/drilldown', {
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
            renderRankRows('memberCountryRanking', (envelope.data.memberCountryRanking && envelope.data.memberCountryRanking.entries) || [], false, 'country');
          } else {
            showToast('Drill-down failed', errorMessage(envelope, 'Unknown error'));
          }
        })
        .catch(function (err) {
          showToast('Drill-down failed', String(err));
        });
  }

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

  // The filter bar is shared across both tabs: a date/region/attack-type
  // change re-fetches the ranking (always visible) plus the drill-down if a
  // region is already selected, so the currently open tab never shows stale
  // data after the filter changes underneath it.
  function onFilterChanged() {
    fetchRanking();
    if (selectedRegionId != null) {
      fetchDrilldown();
    }
  }
})();
