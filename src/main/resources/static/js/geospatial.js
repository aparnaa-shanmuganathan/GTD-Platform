/*
 * Geospatial Workspace client-side wiring (Phase 9, Part B) - Leaflet
 * (vendored locally, CLAUDE.md section 2) with marker clustering, a
 * heatmap toggle, a drawn-region spatial filter, and a client-side
 * timeline scrubber over the already-fetched point set.
 *
 * Base tiles are a local placeholder (every {x}/{y}/{z} request resolves to
 * the same file, window.TILE_URL_TEMPLATE) - the offline tile package is a
 * documented, not-yet-sourced deployment prerequisite (CLAUDE.md section
 * 13, open item 5). Swapping in real tiles is a config change
 * (gtd.geospatial.tile-url-template), not a code change here.
 */
(function () {
  'use strict';

  var map;
  var tileLayer;
  var clusterLayer;
  var heatLayer;
  var drawnItems;
  var drawControl;
  var lastMap = null;
  var currentPolygon = null;
  var timelinePlaying = false;
  var timelineTimer = null;

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

  /* =====================================================================
     MAP INITIALIZATION
     ===================================================================== */
  function initMap() {
    map = L.map(qs('geoMapWrap'), { worldCopyJump: true }).setView([20, 0], 2);

    var PlaceholderTileLayer = L.TileLayer.extend({
      getTileUrl: function () {
        return window.TILE_URL_TEMPLATE;
      }
    });
    tileLayer = new PlaceholderTileLayer('', {
      minZoom: 1,
      maxZoom: 12,
      attribution: 'Placeholder tile layer - offline tile package not yet installed'
    });
    tileLayer.addTo(map);

    clusterLayer = L.markerClusterGroup();
    drawnItems = new L.FeatureGroup();
    map.addLayer(drawnItems);

    drawControl = new L.Control.Draw({
      draw: {
        polygon: true,
        marker: false,
        circle: false,
        circlemarker: false,
        polyline: false,
        rectangle: false
      },
      edit: { featureGroup: drawnItems, remove: false }
    });
    map.addControl(drawControl);

    map.on(L.Draw.Event.CREATED, function (event) {
      drawnItems.clearLayers();
      drawnItems.addLayer(event.layer);
      var latlngs = event.layer.getLatLngs()[0];
      currentPolygon = latlngs.map(function (ll) {
        return [ll.lat, ll.lng];
      });
      qs('clearPolygonBtn').style.display = '';
      applyFilters();
    });
  }

  /* =====================================================================
     POINT LAYER (cluster / heat toggle)
     ===================================================================== */
  function pointsForYearFilter(points, year) {
    if (year == null) {
      return points;
    }
    return points.filter(function (p) {
      return p.year === year;
    });
  }

  function renderPointLayer() {
    if (!lastMap) {
      return;
    }
    var year = currentTimelineYear();
    var points = pointsForYearFilter(lastMap.points || [], year);
    var mode = qs('filterLayer').value;

    clusterLayer.clearLayers();
    map.removeLayer(clusterLayer);
    if (heatLayer) {
      map.removeLayer(heatLayer);
      heatLayer = null;
    }

    if (mode === 'heat') {
      heatLayer = L.heatLayer(points.map(function (p) {
        return [p.lat, p.lng];
      }), { radius: 18, blur: 14 });
      heatLayer.addTo(map);
    } else {
      points.forEach(function (p) {
        var popup = 'Year ' + p.year
            + '<br>Fatalities: ' + (p.nkill != null ? p.nkill : 'unknown')
            + '<br>Injuries: ' + (p.nwound != null ? p.nwound : 'unknown');
        clusterLayer.addLayer(L.marker([p.lat, p.lng]).bindPopup(popup));
      });
      clusterLayer.addTo(map);
    }
  }

  /* =====================================================================
     CHOROPLETH (per-country counts; colored polygons only if boundariesAvailable)
     ===================================================================== */
  function renderChoropleth(mapData) {
    qs('boundaryUnavailableNotice').style.display = mapData.boundariesAvailable ? 'none' : '';

    var entries = mapData.choropleth || [];
    if (entries.length === 0) {
      qs('choroplethRanking').innerHTML = '<p class="insight-empty">No data available for the selected filters.</p>';
      return;
    }
    var maxValue = Math.max.apply(null, entries.map(function (e) {
      return Number(e.value) || 0;
    }));
    var html = entries.map(function (e, i) {
      var pct = maxValue > 0 ? (Number(e.value) / maxValue * 100).toFixed(1) : '0.0';
      return '<div class="rank-row">'
          + '<div class="rank-num">' + String(i + 1).padStart(2, '0') + '</div>'
          + '<div class="rank-item-wrap"><div class="rank-name">' + escapeHtml(e.label) + '</div></div>'
          + '<div class="rank-value">' + fmt(e.value) + '</div>'
          + '<div class="rank-bar-track"><div class="rank-bar-fill" style="width:' + pct + '%"></div></div>'
          + '</div>';
    }).join('');
    qs('choroplethRanking').innerHTML = html;
  }

  /* =====================================================================
     TIMELINE (client-side filter over the already-fetched point set)
     ===================================================================== */
  function currentTimelineYear() {
    var slider = qs('timelineSlider');
    var value = parseInt(slider.value, 10);
    var minYear = window.MIN_YEAR;
    if (minYear == null || value === minYear - 1) {
      return null;
    }
    return value;
  }

  function setupTimeline() {
    var slider = qs('timelineSlider');
    if (window.MIN_YEAR == null || window.MAX_YEAR == null) {
      slider.disabled = true;
      return;
    }
    slider.min = String(window.MIN_YEAR - 1);
    slider.max = String(window.MAX_YEAR);
    slider.value = String(window.MIN_YEAR - 1);
    updateTimelineLabel();

    slider.addEventListener('input', function () {
      updateTimelineLabel();
      renderPointLayer();
    });

    qs('timelinePlayBtn').addEventListener('click', function () {
      timelinePlaying = !timelinePlaying;
      qs('timelinePlayBtn').textContent = timelinePlaying ? 'Pause' : 'Play';
      if (timelinePlaying) {
        timelineTimer = setInterval(stepTimeline, 900);
      } else {
        clearInterval(timelineTimer);
      }
    });
  }

  function stepTimeline() {
    var slider = qs('timelineSlider');
    var next = parseInt(slider.value, 10) + 1;
    if (next > parseInt(slider.max, 10)) {
      next = parseInt(slider.min, 10);
    }
    slider.value = String(next);
    updateTimelineLabel();
    renderPointLayer();
  }

  function updateTimelineLabel() {
    var year = currentTimelineYear();
    qs('timelineYearLabel').textContent = year == null ? 'All years' : String(year);
  }

  /* =====================================================================
     RENDER / FILTER BAR
     ===================================================================== */
  function renderMap(mapData) {
    lastMap = mapData;
    var geocoded = mapData.geocodedCount != null ? mapData.geocodedCount : 0;
    var total = mapData.totalIncidentCount != null ? mapData.totalIncidentCount : 0;
    qs('geocodedHint').textContent = fmt(geocoded) + ' of ' + fmt(total)
        + ' matching incidents have recorded coordinates and are shown on the map.';
    renderPointLayer();
    renderChoropleth(mapData);
  }

  function buildFilterRequest() {
    var filter = { regionIds: [], countryIds: [], attackTypeIds: [], weaponTypeIds: [], targetTypeIds: [], organizationIds: [] };

    var dateSel = qs('filterDate').value;
    if (window.MAX_YEAR != null) {
      var span = dateSel === 'last10' ? 9 : (dateSel === 'last5' ? 4 : null);
      if (span != null) {
        filter.startDate = (window.MAX_YEAR - span) + '-01-01';
        filter.endDate = window.MAX_YEAR + '-12-31';
      }
    }

    var region = qs('filterRegion').value;
    if (region) {
      filter.regionIds = [parseInt(region, 10)];
    }
    var attack = qs('filterAttack').value;
    if (attack) {
      filter.attackTypeIds = [parseInt(attack, 10)];
    }

    return { filter: filter, polygon: currentPolygon };
  }

  function applyFilters() {
    var bar = qs('filterBar');
    bar.classList.add('is-loading');
    fetch('/api/geospatial/points', {
      method: 'POST',
      headers: jsonHeaders(),
      body: JSON.stringify(buildFilterRequest())
    })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (envelope) {
          if (envelope.success) {
            renderMap(envelope.data);
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
    initMap();
    setupTimeline();
    renderMap(window.INITIAL_MAP || { points: [], choropleth: [] });

    qs('filterDate').addEventListener('change', applyFilters);
    qs('filterRegion').addEventListener('change', applyFilters);
    qs('filterAttack').addEventListener('change', applyFilters);
    qs('filterLayer').addEventListener('change', renderPointLayer);

    qs('clearPolygonBtn').addEventListener('click', function () {
      currentPolygon = null;
      drawnItems.clearLayers();
      qs('clearPolygonBtn').style.display = 'none';
      applyFilters();
    });

    qs('themeToggle').addEventListener('click', function () {
      var current = document.documentElement.getAttribute('data-theme');
      var next = current === 'light' ? 'dark' : 'light';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem('gtd-theme', next);
    });

    window.addEventListener('resize', function () {
      if (map) {
        map.invalidateSize();
      }
    });
  });
})();
