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
 *
 * The choropleth layer fetches the vendored Natural Earth 1:110m boundary
 * GeoJSON (GET /api/geospatial/boundaries) once and matches each polygon to
 * a dim_country.name by trying, in order, every property named in
 * window.BOUNDARY_NAME_PROPERTIES (several standard Natural Earth name
 * fields - no single one covers everything, and there's no ISO-code column
 * to join on more robustly). KNOWN_NAME_ALIASES below covers a couple of
 * genuine real-world country renames the source data doesn't reflect
 * (Macedonia -> North Macedonia, Swaziland -> eSwatini). What's left after
 * that - GTD-coded historical/defunct political entities with no modern
 * polygon (Soviet Union, Yugoslavia, Zaire, etc.), plus a handful of
 * micro-states this simplified 1:110m file omits entirely - is a real,
 * disclosed limitation: those countries still show real counts in the
 * ranked list below, just not as a colored polygon.
 */
(function () {
  'use strict';

  var KNOWN_NAME_ALIASES = {
    'North Macedonia': 'Macedonia',
    'eSwatini': 'Swaziland'
  };

  var map;
  var tileLayer;
  var clusterLayer;
  var heatLayer;
  var choroplethLayer;
  var boundaryFeaturesPromise = null;
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

    // Added right after the tile layer (and before markers/heat below) so a colored polygon
    // layer never draws over point markers - Leaflet stacks later-added layers on top.
    choroplethLayer = L.geoJSON(null, { style: choroplethFeatureStyle, onEachFeature: bindChoroplethTooltip });
    choroplethLayer.addTo(map);

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
  var CHOROPLETH_COLOR_SCALE = ['#dbeafe', '#93c5fd', '#60a5fa', '#3b82f6', '#1d4ed8'];
  var choroplethMaxValue = 0;

  function fetchBoundaries() {
    if (!boundaryFeaturesPromise) {
      boundaryFeaturesPromise = fetch('/api/geospatial/boundaries')
          .then(function (resp) {
            if (!resp.ok) {
              throw new Error('boundaries fetch failed: ' + resp.status);
            }
            return resp.json();
          });
    }
    return boundaryFeaturesPromise;
  }

  function candidateNamesForFeature(feature) {
    var props = feature.properties || {};
    var names = [];
    (window.BOUNDARY_NAME_PROPERTIES || []).forEach(function (key) {
      var v = props[key];
      if (v) {
        names.push(v);
        if (KNOWN_NAME_ALIASES[v]) {
          names.push(KNOWN_NAME_ALIASES[v]);
        }
      }
    });
    return names;
  }

  function matchFeatureToEntry(feature, countByLabel) {
    var names = candidateNamesForFeature(feature);
    for (var i = 0; i < names.length; i++) {
      if (Object.prototype.hasOwnProperty.call(countByLabel, names[i])) {
        return { label: names[i], value: countByLabel[names[i]] };
      }
    }
    return null;
  }

  function colorForValue(value) {
    if (!value || value <= 0 || choroplethMaxValue <= 0) {
      return CHOROPLETH_COLOR_SCALE[0];
    }
    var t = Math.log(1 + value) / Math.log(1 + choroplethMaxValue);
    var idx = Math.round(t * (CHOROPLETH_COLOR_SCALE.length - 1));
    return CHOROPLETH_COLOR_SCALE[Math.max(0, Math.min(CHOROPLETH_COLOR_SCALE.length - 1, idx))];
  }

  function choroplethFeatureStyle(feature) {
    var matched = feature.properties && feature.properties.__matched;
    return {
      fillColor: matched ? colorForValue(feature.properties.__value) : 'transparent',
      fillOpacity: matched ? 0.75 : 0,
      color: matched ? 'rgba(15,23,42,0.35)' : 'transparent',
      weight: 1
    };
  }

  function bindChoroplethTooltip(feature, layer) {
    if (feature.properties && feature.properties.__matched) {
      layer.bindTooltip(feature.properties.__label + ': ' + fmt(feature.properties.__value), { sticky: true });
    }
  }

  function renderLegend() {
    if (choroplethMaxValue <= 0) {
      qs('choroplethLegend').style.display = 'none';
      return;
    }
    var swatches = CHOROPLETH_COLOR_SCALE.map(function (c) {
      return '<span style="display:inline-block;width:18px;height:12px;background:' + c + ';border-radius:2px;"></span>';
    }).join('');
    qs('choroplethLegend').innerHTML = 'Incidents per country: 1 '
        + '<span style="display:inline-flex;gap:2px;vertical-align:middle;margin:0 6px;">' + swatches + '</span> '
        + fmt(choroplethMaxValue) + ' (log-scaled)';
    qs('choroplethLegend').style.display = '';
  }

  function renderChoroplethPolygons(mapData) {
    if (!mapData.boundariesAvailable || !qs('filterChoropleth').checked) {
      choroplethLayer.clearLayers();
      choroplethMaxValue = 0;
      qs('choroplethLegend').style.display = 'none';
      qs('choroplethMatchNotice').style.display = 'none';
      return;
    }

    var entries = mapData.choropleth || [];
    var countByLabel = {};
    entries.forEach(function (e) {
      countByLabel[e.label] = Number(e.value) || 0;
    });
    choroplethMaxValue = entries.reduce(function (max, e) {
      return Math.max(max, Number(e.value) || 0);
    }, 0);

    fetchBoundaries().then(function (featureCollection) {
      var matchedLabels = {};
      var features = (featureCollection.features || []).map(function (feature) {
        var match = matchFeatureToEntry(feature, countByLabel);
        var clone = Object.assign({}, feature, { properties: Object.assign({}, feature.properties) });
        if (match) {
          clone.properties.__matched = true;
          clone.properties.__label = match.label;
          clone.properties.__value = match.value;
          matchedLabels[match.label] = true;
        } else {
          clone.properties.__matched = false;
        }
        return clone;
      });

      choroplethLayer.clearLayers();
      choroplethLayer.addData({ type: 'FeatureCollection', features: features });
      renderLegend();

      var matchedCount = Object.keys(matchedLabels).length;
      var noticeEl = qs('choroplethMatchNotice');
      if (matchedCount < entries.length) {
        noticeEl.textContent = matchedCount + ' of ' + entries.length + ' countries with incidents are shown as '
            + 'colored polygons. The rest (historical/defunct political entities GTD codes separately, or '
            + 'micro-states this simplified boundary file omits) still show real counts in the ranked list below.';
        noticeEl.style.display = '';
      } else {
        noticeEl.style.display = 'none';
      }
    }).catch(function (err) {
      showToast('Choropleth failed to load', String(err));
    });
  }

  function renderChoroplethRanking(mapData) {
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

  function renderChoropleth(mapData) {
    qs('boundaryUnavailableNotice').style.display = mapData.boundariesAvailable ? 'none' : '';
    renderChoroplethPolygons(mapData);
    renderChoroplethRanking(mapData);
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
    qs('filterChoropleth').addEventListener('change', function () {
      if (lastMap) {
        renderChoroplethPolygons(lastMap);
      }
    });

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
