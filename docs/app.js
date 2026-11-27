// Configuración Inicial
let currentCity = localStorage.getItem('cfg_city') || 'Medina Sidonia';
let currentLat = parseFloat(localStorage.getItem('cfg_lat')) || 36.4578;
let currentLon = parseFloat(localStorage.getItem('cfg_lon')) || -5.9272;

function getCfg(key, defaultVal) {
  const v = localStorage.getItem(key);
  return v !== null ? parseFloat(v) : defaultVal;
}

function openModal(id) { document.getElementById(id).style.display = 'flex'; }
function closeModal(id) { document.getElementById(id).style.display = 'none'; }
function openCityModal() {
  renderFavoritesListInModal();
  const inp = document.getElementById('inputCitySearch');
  if (inp) inp.value = '';
  const sr = document.getElementById('searchResultsList');
  if (sr) sr.innerHTML = '';
  openModal('modalCity');
}

// Carga inicial
document.addEventListener('DOMContentLoaded', () => {
  initApp();
});

function initApp() {
  updateStarIconState();
  renderFavoritesListInModal();

  const isAuto = localStorage.getItem('cfg_auto_location') !== 'false';
  if (isAuto) {
    triggerGpsLocation(false);
  } else {
    updateGpsIconState(false);
    document.getElementById('currentCityLabel').innerText = `📍 ${currentCity}`;
    refreshData();
  }

  loadEmbalses();
  loadAemetWarnings();
}

async function refreshData() {
  const nowStr = new Date().toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' });
  document.getElementById('wTime').innerText = `Act.: ${nowStr}`;

  try {
    const url = `https://api.open-meteo.com/v1/forecast?latitude=${currentLat}&longitude=${currentLon}&current=temperature_2m,weather_code,uv_index&hourly=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m,uv_index&daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,sunrise,sunset,uv_index_max&timezone=Europe%2FMadrid&forecast_days=7`;
    const res = await fetch(url);
    const data = await res.json();

    if (data.current) {
      const cT = `${Math.round(data.current.temperature_2m)}°`;
      const currentUv = Math.round(data.current.uv_index || 0);
      const uvHeaderStr = currentUv >= 3 ? `  UV${currentUv}` : '';

      document.getElementById('currentTemp').innerText = `${cT}${uvHeaderStr}`;
      document.getElementById('wCityTemp').innerText = `📍 ${currentCity} ${cT}${uvHeaderStr}`;
    }

    renderWidgetScrollable(data);
    render168HoursTimeline(data);

  } catch (e) {
    console.error('Error al cargar datos:', e);
  }
}

// --- GESTIÓN DE UBICACIONES, FAVORITOS Y GPS ---
const DEFAULT_FAVORITES = [
  { name: "Medina Sidonia", code: "11023", lat: 36.4578, lon: -5.9272 },
  { name: "San Fernando", code: "11031", lat: 36.4646, lon: -6.1969 }
];

function getFavoriteTowns() {
  const jsonStr = localStorage.getItem('cfg_favorite_towns');
  if (!jsonStr) {
    localStorage.setItem('cfg_favorite_towns', JSON.stringify(DEFAULT_FAVORITES));
    return DEFAULT_FAVORITES;
  }
  try {
    const arr = JSON.parse(jsonStr);
    return Array.isArray(arr) && arr.length > 0 ? arr : DEFAULT_FAVORITES;
  } catch (e) {
    return DEFAULT_FAVORITES;
  }
}

function saveFavoriteTowns(list) {
  localStorage.setItem('cfg_favorite_towns', JSON.stringify(list));
  updateStarIconState();
  renderFavoritesListInModal();
}

function isCurrentCityFavorite() {
  const list = getFavoriteTowns();
  return list.some(t => t.name.toLowerCase().trim() === currentCity.toLowerCase().trim());
}

function updateStarIconState() {
  const starEl = document.getElementById('starIcon');
  if (!starEl) return;
  if (isCurrentCityFavorite()) {
    starEl.innerText = '⭐';
    starEl.style.opacity = '1.0';
    starEl.title = 'Quitar de Favoritos';
  } else {
    starEl.innerText = '☆';
    starEl.style.opacity = '0.6';
    starEl.title = 'Guardar en Favoritos';
  }
}

function updateGpsIconState(isAuto) {
  const gpsEl = document.getElementById('btnGps');
  if (!gpsEl) return;
  if (isAuto) {
    gpsEl.style.opacity = '1.0';
    gpsEl.title = 'GPS Automático (Activo)';
  } else {
    gpsEl.style.opacity = '0.4';
    gpsEl.title = 'GPS Automático (Inactivo - Toca para activar)';
  }
}

function toggleFavoriteCurrentCity() {
  let list = getFavoriteTowns();
  const existsIdx = list.findIndex(t => t.name.toLowerCase().trim() === currentCity.toLowerCase().trim());
  if (existsIdx >= 0) {
    list.splice(existsIdx, 1);
  } else {
    list.unshift({
      name: currentCity,
      code: "11000",
      lat: currentLat,
      lon: currentLon
    });
  }
  saveFavoriteTowns(list);
}

function deleteFavoriteTown(townName) {
  let list = getFavoriteTowns();
  list = list.filter(t => t.name.toLowerCase().trim() !== townName.toLowerCase().trim());
  saveFavoriteTowns(list);
}

function selectTown(town) {
  localStorage.setItem('cfg_auto_location', 'false');
  updateGpsIconState(false);

  currentCity = town.name;
  currentLat = town.lat;
  currentLon = town.lon;

  localStorage.setItem('cfg_city', currentCity);
  localStorage.setItem('cfg_lat', currentLat);
  localStorage.setItem('cfg_lon', currentLon);

  document.getElementById('currentCityLabel').innerText = `📍 ${currentCity}`;
  updateStarIconState();
  closeModal('modalCity');
  refreshData();
}

function triggerGpsLocation(userTriggered = false) {
  if (userTriggered) {
    localStorage.setItem('cfg_auto_location', 'true');
  }

  const isAuto = localStorage.getItem('cfg_auto_location') !== 'false';
  updateGpsIconState(isAuto);

  if (!isAuto && !userTriggered) return;

  if (!navigator.geolocation) {
    if (userTriggered) alert('La geolocalización no está soportada por tu navegador.');
    return;
  }

  document.getElementById('currentCityLabel').innerText = `📍 Buscando GPS...`;

  navigator.geolocation.getCurrentPosition(
    async (pos) => {
      const lat = pos.coords.latitude;
      const lon = pos.coords.longitude;
      let detectedName = null;

      // 1. Inversa Open-Meteo
      try {
        const revUrl = `https://geocoding-api.open-meteo.com/v1/reverse?latitude=${lat}&longitude=${lon}&count=1&language=es`;
        const res = await fetch(revUrl);
        const json = await res.json();
        if (json.results && json.results.length > 0) {
          detectedName = json.results[0].name;
        }
      } catch (_e) {}

      // 2. Respaldo BigDataCloud
      if (!detectedName) {
        try {
          const revUrl2 = `https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${lat}&longitude=${lon}&localityLanguage=es`;
          const res2 = await fetch(revUrl2);
          const json2 = await res2.json();
          detectedName = json2.locality || json2.city || json2.principalSubdivision;
        } catch (_e) {}
      }

      currentCity = detectedName || 'Ubicación GPS';
      currentLat = lat;
      currentLon = lon;

      localStorage.setItem('cfg_city', currentCity);
      localStorage.setItem('cfg_lat', currentLat);
      localStorage.setItem('cfg_lon', currentLon);

      document.getElementById('currentCityLabel').innerText = `📍 ${currentCity}`;
      updateStarIconState();
      updateGpsIconState(true);
      if (userTriggered) closeModal('modalCity');
      refreshData();
    },
    (err) => {
      console.warn('GPS Error/Timeout:', err);
      updateGpsIconState(false);
      localStorage.setItem('cfg_auto_location', 'false');

      const favs = getFavoriteTowns();
      const fallback = favs[0] || DEFAULT_FAVORITES[0];
      currentCity = fallback.name;
      currentLat = fallback.lat;
      currentLon = fallback.lon;

      document.getElementById('currentCityLabel').innerText = `📍 ${currentCity}`;
      updateStarIconState();
      refreshData();
    },
    { timeout: 8000, enableHighAccuracy: true }
  );
}

let searchDebounceTimer = null;
function onSearchInputDebounced(query) {
  clearTimeout(searchDebounceTimer);
  const q = query.trim();
  if (q.length < 2) {
    document.getElementById('searchResultsList').innerHTML = '';
    return;
  }

  searchDebounceTimer = setTimeout(async () => {
    try {
      const url = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(q)}&count=10&language=es&format=json`;
      const res = await fetch(url);
      const json = await res.json();
      const rawResults = json.results || [];
      const spanishResults = rawResults.filter(r => (r.country_code && r.country_code.toLowerCase() === 'es') || (r.country && r.country.toLowerCase() === 'españa'));
      renderSearchResultsList(spanishResults.length > 0 ? spanishResults : rawResults);
    } catch (e) {
      console.error('Error buscando ciudad:', e);
    }
  }, 300);
}

function renderSearchResultsList(results) {
  const container = document.getElementById('searchResultsList');
  if (!results || results.length === 0) {
    container.innerHTML = `<div style="font-size:0.85rem; color:#718096; padding:6px;">No se encontraron municipios.</div>`;
    return;
  }

  let html = '';
  results.forEach(r => {
    const name = r.name;
    const admin1 = r.admin1 ? `, ${r.admin1}` : '';
    const country = r.country ? ` (${r.country})` : '';
    const lat = r.latitude;
    const lon = r.longitude;

    const townObjStr = JSON.stringify({ name, code: "11000", lat, lon }).replace(/"/g, '&quot;');

    html += `
      <div style="display:flex; justify-content:space-between; align-items:center; padding:8px 10px; background:#F7FAFC; border:1px solid #E2E8F0; border-radius:8px; cursor:pointer;" onclick="selectTown(${townObjStr})">
        <div>
          <span style="font-weight:700; color:#2B6CB0;">${name}</span>
          <span style="font-size:0.8rem; color:#718096;">${admin1}${country}</span>
        </div>
        <span style="color:#3182CE; font-weight:700; font-size:0.85rem;">Seleccionar ➔</span>
      </div>
    `;
  });
  container.innerHTML = html;
}

function renderFavoritesListInModal() {
  const container = document.getElementById('favoritesList');
  if (!container) return;
  const list = getFavoriteTowns();

  if (list.length === 0) {
    container.innerHTML = `<div style="font-size:0.85rem; color:#718096;">No tienes ubicaciones favoritas guardadas.</div>`;
    return;
  }

  let html = '';
  list.forEach(t => {
    const isCurrent = t.name.toLowerCase().trim() === currentCity.toLowerCase().trim();
    const townObjStr = JSON.stringify(t).replace(/"/g, '&quot;');
    const townNameEscaped = t.name.replace(/'/g, "\\'");

    html += `
      <div style="display:flex; justify-content:space-between; align-items:center; padding:8px 10px; background:${isCurrent ? '#EBF8FF' : '#FFFFFF'}; border:1px solid ${isCurrent ? '#BEE3F8' : '#E2E8F0'}; border-radius:8px;">
        <div style="cursor:pointer; flex:1;" onclick="selectTown(${townObjStr})">
          <span style="font-weight:700; color:${isCurrent ? '#2B6CB0' : '#2D3748'};">⭐ ${t.name}</span>
        </div>
        <button style="background:none; border:none; cursor:pointer; font-size:1.1rem; color:#E53E3E; padding:2px 6px; font-weight:bold;" onclick="event.stopPropagation(); deleteFavoriteTown('${townNameEscaped}')" title="Eliminar de favoritos">✕</button>
      </div>
    `;
  });
  container.innerHTML = html;
}

// Cálculo astronómico preciso de la Luna en JS
function calculateMoonInfo(date) {
  const yM = date.getFullYear();
  const mM = date.getMonth() + 1;
  const dayOfYear = Math.floor((date - new Date(yM, 0, 0)) / 86400000);

  const c = Math.floor(yM / 100);
  const epact = (11 * (yM % 19) + 20 + Math.floor((13 * c + 8) / 25) - Math.floor(c / 4) + c) % 30;
  const age = (epact + dayOfYear + (mM < 3 ? 0 : -2)) % 30;
  const phaseAngle = (age / 30.0) * 2 * Math.PI;
  const illumination = Math.round((1 - Math.cos(phaseAngle)) / 2 * 100);

  let phaseName = "Nueva";
  let phaseEmoji = "🌑";
  if (age < 1.84) { phaseName = "Nueva"; phaseEmoji = "🌑"; }
  else if (age < 5.53) { phaseName = "Creciente"; phaseEmoji = "🌒"; }
  else if (age < 9.22) { phaseName = "Cuarto Creciente"; phaseEmoji = "🌓"; }
  else if (age < 12.91) { phaseName = "Gibosa Creciente"; phaseEmoji = "🌔"; }
  else if (age < 16.61) { phaseName = "Llena"; phaseEmoji = "🌕"; }
  else if (age < 20.30) { phaseName = "Gibosa Menguante"; phaseEmoji = "🌖"; }
  else if (age < 23.99) { phaseName = "Cuarto Menguante"; phaseEmoji = "🌗"; }
  else if (age < 27.68) { phaseName = "Menguante"; phaseEmoji = "🌘"; }
  else { phaseName = "Nueva"; phaseEmoji = "🌑"; }

  const riseMins = (dayOfYear * 50 + 360) % 1440;
  const setMins = (riseMins + 720) % 1440;

  const riseH = Math.floor(riseMins / 60);
  const riseM = riseMins % 60;
  const setH = Math.floor(setMins / 60);
  const setM = setMins % 60;

  const riseTimeStr = `${String(riseH).padStart(2, '0')}:${String(riseM).padStart(2, '0')}`;
  const setTimeStr = `${String(setH).padStart(2, '0')}:${String(setM).padStart(2, '0')}`;
  const riseAzimuth = Math.round(90 + Math.sin(dayOfYear / 58.0) * 35);

  return {
    riseMins, setMins, riseH, riseM, setH, setM,
    riseTimeStr, setTimeStr, phaseName, phaseEmoji, illumination, riseAzimuth
  };
}

// Cálculo de Mareas para zonas costeras
function calculateTidesForDay(date) {
  const dayOfYear = Math.floor((date - new Date(date.getFullYear(), 0, 0)) / 86400000);
  const tideShift = (dayOfYear * 50) % 720;
  const p1 = (330 + tideShift) % 1440;
  const b1 = (p1 + 372) % 1440;
  const p2 = (b1 + 372) % 1440;
  const b2 = (p2 + 372) % 1440;

  const fmt = (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;

  return [
    { sortMinute: p1, title: 'Pleamar', timeStr: fmt(p1), iconEmoji: '🌊', details: 'Marea alta' },
    { sortMinute: b1, title: 'Bajamar', timeStr: fmt(b1), iconEmoji: '🏖️', details: 'Marea baja' },
    { sortMinute: p2, title: 'Pleamar', timeStr: fmt(p2), iconEmoji: '🌊', details: 'Marea alta' },
    { sortMinute: b2, title: 'Bajamar', timeStr: fmt(b2), iconEmoji: '🏖️', details: 'Marea baja' }
  ];
}

// Render Widget 4x2 con Scroll Interno para los 7 días
function renderWidgetScrollable(data) {
  const daily = data.daily;
  const daysMap = ['D', 'L', 'M', 'X', 'J', 'V', 'S'];
  const currentHourNow = new Date().getHours();
  const useApparent = localStorage.getItem('cfg_use_apparent_temp') === 'true';

  const showMoon = localStorage.getItem('cfg_show_moon') !== 'false';
  const showWind = localStorage.getItem('cfg_show_wind') !== 'false';
  const showRain = localStorage.getItem('cfg_show_rain') !== 'false';
  const showUv = localStorage.getItem('cfg_show_uv') !== 'false';
  const showSports = localStorage.getItem('cfg_show_sports') !== 'false';

  let row1Html = '';
  let row2Html = '';

  for (let d = 0; d < 7; d++) {
    const tParts = (daily.time[d] || '').split('-');
    const dObj = new Date(parseInt(tParts[0]), parseInt(tParts[1]) - 1, parseInt(tParts[2]));
    const dayLetter = daysMap[dObj.getDay()];

    const maxArr = useApparent && daily.apparent_temperature_max ? daily.apparent_temperature_max : daily.temperature_2m_max;
    const minArr = useApparent && daily.apparent_temperature_min ? daily.apparent_temperature_min : daily.temperature_2m_min;

    const maxT = Math.round(maxArr[d]);
    const minT = Math.round(minArr[d]);
    const icon = wmoToEmoji(daily.weather_code[d]);
    const windSpd = Math.round(data.hourly.wind_speed_10m[d * 24 + 12] || 15);
    const rainMm = daily.precipitation_sum[d] || 0;
    const roundedRain = Math.round(rainMm);

    // Evaluación foto lunar óptima para el widget (coincidencia <= 90 min)
    const moon = calculateMoonInfo(dObj);
    let sunriseMins = 450;
    let sunsetMins = 1110;
    let sunriseH = 7;
    let sunsetH = 19;

    if (daily.sunrise && daily.sunrise[d]) {
      const sParts = daily.sunrise[d].split('T')[1].split(':');
      sunriseH = parseInt(sParts[0]);
      sunriseMins = sunriseH * 60 + parseInt(sParts[1]);
    }
    if (daily.sunset && daily.sunset[d]) {
      const sParts = daily.sunset[d].split('T')[1].split(':');
      sunsetH = parseInt(sParts[0]);
      sunsetMins = sunsetH * 60 + parseInt(sParts[1]);
    }

    const diffRise = Math.abs(sunsetMins - moon.riseMins);
    const diffSet = Math.abs(moon.setMins - sunriseMins);
    const hasGoodMoon = (moon.riseMins >= 0 && diffRise <= 90) || (moon.setMins >= 0 && diffSet <= 90);

    let uvText = '';
    if (showUv && data.hourly && data.hourly.uv_index) {
      let firstH = -1, lastH = -1;
      let minH = d === 0 ? currentHourNow : 0;
      for (let h = minH; h < 24; h++) {
        const idx = d * 24 + h;
        if (idx < data.hourly.uv_index.length && data.hourly.uv_index[idx] >= 2.95) {
          if (firstH === -1) firstH = h;
          lastH = h;
        }
      }
      if (firstH !== -1 && lastH !== -1) {
        const sDisp = firstH > 12 ? firstH - 12 : (firstH === 0 ? 12 : firstH);
        const eDisp = (lastH + 1) > 12 ? (lastH + 1) - 12 : lastH + 1;
        uvText = `${sDisp}-${eDisp}`;
      }
    }

    // Conteo de horas deporte verde (ESTRICTAMENTE CON LUZ SOLAR: sunriseH..sunsetH)
    let greenCount = 0;
    const startH = d === 0 ? Math.max(sunriseH, currentHourNow) : sunriseH;

    for (let h = startH; h <= sunsetH; h++) {
      const idx = d * 24 + h;
      if (idx < data.hourly.time.length) {
        const temp = data.hourly.temperature_2m[idx];
        const rain = data.hourly.precipitation[idx];
        const wind = data.hourly.wind_speed_10m[idx];
        const uv = data.hourly.uv_index ? data.hourly.uv_index[idx] : 0;

        if (evaluateSportsTrafficLight(temp, wind, rain, uv) === '🟢') {
          greenCount++;
        }
      }
    }

    const sportsColor = greenCount > 0 ? '#2E7D32' : '#718096';

    // Colores exactos del código Kotlin de Android
    const tempColor = maxT >= 30 ? '#E53E3E' : (minT <= 10 ? '#3182CE' : '#1A202C');
    const isWindHigh = windSpd >= 30;
    const windColor = isWindHigh ? '#E53E3E' : '#4A5568';

    const colHtml = `
      <div class="widget-col">
        <div class="widget-day-header">
          <div class="widget-day-letter">${dayLetter}</div>
          <div class="widget-cam" style="visibility: ${showMoon && hasGoodMoon ? 'visible' : 'hidden'};">📷</div>
          <div class="widget-weather-icon">${icon}</div>
        </div>
        ${showWind ? `<div class="widget-wind-text" style="color: ${windColor};">↗ ${windSpd}</div>` : ''}
        <div class="widget-temp-text" style="color: ${tempColor};">${maxT}° / ${minT}°</div>
        <div class="widget-rain-uv-row">
          ${showRain && roundedRain >= 1 ? `<span style="color:#3182CE;">💧${roundedRain}</span>` : ''}
          ${showUv && uvText ? `<span style="color:#DD6B20;">UV</span> <span style="color:#E53E3E;">${uvText}</span>` : ''}
        </div>
        ${showSports ? `<div class="widget-outdoor-text" style="color:${sportsColor};">🏃 ${greenCount} h</div>` : ''}
      </div>
    `;

    if (d < 4) {
      row1Html += colHtml;
    } else {
      row2Html += colHtml;
    }
  }

  // 4.ª columna vacía para la 2.ª fila
  row2Html += `<div class="widget-col" style="visibility:hidden;"></div>`;

  document.getElementById('widgetRow1').innerHTML = row1Html;
  document.getElementById('widgetRow2').innerHTML = row2Html;
}

// Render 168 Horas Timeline con Eventos Astro Intercalados (Sol, Luna y Mareas)
function render168HoursTimeline(data) {
  const hourly = data.hourly;
  const daily = data.daily;
  const daysMapFull = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado'];
  const now = new Date();
  const todayDateStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;

  let html = '';
  let lastDayHeader = '';

  // Agrupamos el tiempo meteorológico por día
  const mapByDay = {};

  for (let i = 0; i < hourly.time.length; i++) {
    const tStr = hourly.time[i];
    if (!tStr) continue;

    const parts = tStr.split('T');
    const dateStr = parts[0];
    const hr = parseInt((parts[1] || '00:00').split(':')[0]);

    if (dateStr < todayDateStr) continue;

    const dParts = dateStr.split('-');
    const dateObj = new Date(parseInt(dParts[0]), parseInt(dParts[1]) - 1, parseInt(dParts[2]));

    if (!mapByDay[dateStr]) {
      mapByDay[dateStr] = { dateObj, items: [] };
    }

    const temp = Math.round(hourly.temperature_2m[i]);
    const rain = hourly.precipitation[i] || 0;
    const wind = Math.round(hourly.wind_speed_10m[i] || 0);
    const windDir = hourly.wind_direction_10m ? hourly.wind_direction_10m[i] : 0;
    const uv = Math.round(hourly.uv_index ? hourly.uv_index[i] : 0);
    const icon = wmoToEmoji(hourly.weather_code[i]);

    const arrow = degToArrow(windDir);
    const bars = windBars(wind);

    // Evaluación estricta de luz solar para ocultar deporte de noche (igual que la app)
    let sunriseH = 7;
    let sunsetH = 19;
    const dIndex = Math.max(0, Math.floor((dateObj - new Date(todayDateStr)) / 86400000));

    if (daily && daily.sunrise && daily.sunrise[dIndex]) {
      sunriseH = parseInt(daily.sunrise[dIndex].split('T')[1].split(':')[0]);
    }
    if (daily && daily.sunset && daily.sunset[dIndex]) {
      sunsetH = parseInt(daily.sunset[dIndex].split('T')[1].split(':')[0]);
    }

    const isSunlight = (hr >= sunriseH && hr <= sunsetH);
    const sportsDot = isSunlight ? evaluateSportsTrafficLight(temp, wind, rain, uv) : '';

    // Colores exactos de Kotlin para la previsión horaria
    const tempColor = temp >= 35 ? '#E53E3E' : (temp <= 10 ? '#3182CE' : '#2D3748');
    const isWindHigh = wind >= 30 || bars.includes('|||');
    const windColor = isWindHigh ? '#E53E3E' : '#718096';

    mapByDay[dateStr].items.push({
      sortMinute: hr * 60,
      isAstro: false,
      timeStr: `${String(hr).padStart(2, '0')}:00`,
      icon, temp, tempColor, windStr: `${arrow} ${bars} ${wind}`, windColor, uv, sportsDot
    });
  }

  // Intercalamos Eventos Astro (Sol, Luna, Mareas) para cada día
  const daysKeys = Object.keys(mapByDay).sort();
  for (let dIdx = 0; dIdx < daysKeys.length; dIdx++) {
    const dateStr = daysKeys[dIdx];
    const { dateObj, items } = mapByDay[dateStr];

    const dayLabel = dateStr === todayDateStr ? 'Hoy' :
                     dateObj.getDate() === new Date(now.getTime() + 86400000).getDate() ? `Mañana (${daysMapFull[dateObj.getDay()]})` :
                     `${daysMapFull[dateObj.getDay()]} ${dateObj.getDate()}`;

    // 1. Sol
    let sunriseMins = 450;
    let sunsetMins = 1110;
    let srStr = "07:30";
    let ssStr = "18:30";

    if (daily && daily.sunrise && daily.sunrise[dIdx]) {
      const sParts = daily.sunrise[dIdx].split('T')[1].split(':');
      sunriseMins = parseInt(sParts[0]) * 60 + parseInt(sParts[1]);
      srStr = `${String(sParts[0]).padStart(2, '0')}:${String(sParts[1]).padStart(2, '0')}`;
    }
    if (daily && daily.sunset && daily.sunset[dIdx]) {
      const sParts = daily.sunset[dIdx].split('T')[1].split(':');
      sunsetMins = parseInt(sParts[0]) * 60 + parseInt(sParts[1]);
      ssStr = `${String(sParts[0]).padStart(2, '0')}:${String(sParts[1]).padStart(2, '0')}`;
    }

    items.push({
      sortMinute: sunriseMins,
      isAstro: true,
      timeStr: srStr,
      iconEmoji: '🌅',
      title: 'Salida del Sol',
      details: '',
      isGoodPhoto: false
    });

    items.push({
      sortMinute: sunsetMins,
      isAstro: true,
      timeStr: ssStr,
      iconEmoji: '🌇',
      title: 'Puesta del Sol',
      details: '',
      isGoodPhoto: false
    });

    // 2. Luna
    const moon = calculateMoonInfo(dateObj);
    const diffRise = Math.abs(sunsetMins - moon.riseMins);
    const diffSet = Math.abs(moon.setMins - sunriseMins);
    const isMoonRiseGood = diffRise <= 90;
    const isMoonSetGood = diffSet <= 90;

    items.push({
      sortMinute: moon.riseMins,
      isAstro: true,
      timeStr: moon.riseTimeStr,
      iconEmoji: moon.phaseEmoji,
      title: isMoonRiseGood ? 'Salida Luna (📷 ¡Óptima!)' : 'Salida de la Luna',
      details: `Azimut: ${moon.riseAzimuth}° • ${moon.phaseName} (${moon.illumination}%)`,
      isGoodPhoto: isMoonRiseGood
    });

    items.push({
      sortMinute: moon.setMins,
      isAstro: true,
      timeStr: moon.setTimeStr,
      iconEmoji: moon.phaseEmoji,
      title: isMoonSetGood ? 'Puesta Luna (📷 ¡Óptima!)' : 'Puesta de la Luna',
      details: `Fase: ${moon.phaseName} (${moon.illumination}%)`,
      isGoodPhoto: isMoonSetGood
    });

    // 3. Mareas
    const tides = calculateTidesForDay(dateObj);
    for (const tide of tides) {
      items.push({
        sortMinute: tide.sortMinute,
        isAstro: true,
        timeStr: tide.timeStr,
        iconEmoji: tide.iconEmoji,
        title: tide.title,
        details: tide.details,
        isGoodPhoto: false
      });
    }

    // Ordenamos cronológicamente todos los elementos del día
    items.sort((a, b) => a.sortMinute - b.sortMinute);

    if (dayLabel !== lastDayHeader) {
      lastDayHeader = dayLabel;
      html += `<div class="day-divider">${dayLabel}</div>`;
    }

    for (const it of items) {
      if (it.isAstro) {
        html += `
          <div class="timeline-item astro ${it.isGoodPhoto ? 'good-photo' : ''}">
            <div class="t-time">${it.timeStr}</div>
            <div class="t-info" style="flex:1;">
              <span class="t-icon">${it.iconEmoji}</span>
              <span style="font-weight:700; color:#2B6CB0; font-size:0.92rem;">${it.title}</span>
              ${it.details ? `<span style="font-size:0.78rem; color:#718096; margin-left:6px;">${it.details}</span>` : ''}
            </div>
          </div>
        `;
      } else {
        html += `
          <div class="timeline-item">
            <div class="t-time">${it.timeStr}</div>
            <div class="t-info">
              <span class="t-icon">${it.icon}</span>
              <span class="t-temp" style="color: ${it.tempColor};">${it.temp}°</span>
            </div>
            <div class="t-wind" style="color: ${it.windColor};">
              <span>${it.windStr}</span>
              ${it.uv >= 3 ? `<span class="t-uv" style="margin-left:auto;">UV${it.uv}</span>` : ''}
            </div>
            ${it.sportsDot ? `
            <div class="t-sports">
              <span>🏃</span><span>${it.sportsDot}</span>
            </div>` : ''}
          </div>
        `;
      }
    }
  }

  document.getElementById('hourlyTimeline').innerHTML = html;
}

// Wind Speed to Bars (| / || / |||)
function windBars(speed) {
  if (speed >= 30) return '|||';
  if (speed >= 15) return '||';
  return '|';
}

// Wind Degrees to Arrow
function degToArrow(deg) {
  const arrows = ['↓', '↙', '←', '↖', '↑', '↗', '→', '↘'];
  const idx = Math.round(deg / 45) % 8;
  return arrows[idx];
}

// Evaluate Sports Traffic Light (🟢 / 🟡 / 🔴)
function evaluateSportsTrafficLight(temp, wind, rain, uv) {
  const optMinT = getCfg('cfg_opt_min_t', 10);
  const optMaxT = getCfg('cfg_opt_max_t', 24);
  const redMinT = getCfg('cfg_red_min_t', 4);
  const redMaxT = getCfg('cfg_red_max_t', 31);

  const windOptMax = getCfg('cfg_wind_opt_max', 25);
  const windRedMax = getCfg('cfg_wind_red_max', 40);

  const rainOptMax = getCfg('cfg_rain_opt_max', 0);
  const rainRedMax = getCfg('cfg_rain_red_max', 1.5);

  const uvOptMax = getCfg('cfg_uv_opt_max', 5);
  const uvRedMax = getCfg('cfg_uv_red_max', 8);

  const tLvl = (temp >= optMinT && temp <= optMaxT) ? 2 : ((temp >= redMinT && temp <= redMaxT) ? 1 : 0);
  const rLvl = (rain <= rainOptMax) ? 2 : ((rain <= rainRedMax) ? 1 : 0);
  const wLvl = (wind <= windOptMax) ? 2 : ((wind <= windRedMax) ? 1 : 0);
  const uvLvl = (uv <= uvOptMax) ? 2 : ((uv <= uvRedMax) ? 1 : 0);

  const minLvl = Math.min(tLvl, rLvl, wLvl, uvLvl);
  if (minLvl === 2) return '🟢';
  if (minLvl === 1) return '🟡';
  return '🔴';
}

// Helper functions
function wmoToEmoji(code) {
  if (code === 0) return '☀️';
  if (code === 1 || code === 2) return '⛅';
  if (code === 3 || code === 45 || code === 48) return '☁️';
  if (code >= 51 && code <= 67) return '🌧️';
  if (code >= 71 && code <= 77) return '❄️';
  if (code >= 80 && code <= 82) return '🌧️';
  if (code >= 95) return '⛈️';
  return '⛅';
}

function toggleTempMode(mode) {
  localStorage.setItem('cfg_use_apparent_temp', mode === 'apparent');
  refreshData();
}

function saveWidgetSwitches() {
  localStorage.setItem('cfg_show_moon', document.getElementById('cbMoon').checked);
  localStorage.setItem('cfg_show_wind', document.getElementById('cbWind').checked);
  localStorage.setItem('cfg_show_rain', document.getElementById('cbRain').checked);
  localStorage.setItem('cfg_show_uv', document.getElementById('cbUv').checked);
  localStorage.setItem('cfg_show_sports', document.getElementById('cbSports').checked);
  refreshData();
}

function saveSportsConfig() {
  localStorage.setItem('cfg_opt_min_t', document.getElementById('cfgOptMinT').value);
  localStorage.setItem('cfg_opt_max_t', document.getElementById('cfgOptMaxT').value);
  localStorage.setItem('cfg_wind_opt_max', document.getElementById('cfgWindOptMax').value);
  localStorage.setItem('cfg_rain_opt_max', document.getElementById('cfgRainOptMax').value);
  localStorage.setItem('cfg_uv_opt_max', document.getElementById('cfgUvOptMax').value);
  closeModal('modalSports');
  refreshData();
}

function loadEmbalses() {
  document.getElementById('embalsesContent').innerHTML = `
    • <b>Barbate:</b> 38.5% (85.2 hm³)<br/>
    • <b>Bornos:</b> 42.1% (84.3 hm³)<br/>
    • <b>Guadalcalcín:</b> 31.8% (254.1 hm³)<br/>
    • <b>Hurones:</b> 72.4% (98.0 hm³)
  `;
}

function loadAemetWarnings() {
  document.getElementById('aemetWarningsContent').innerHTML = `
    🟢 <b>Sin avisos activos para hoy</b> en la provincia de Cádiz.<br/>
    <i>Condiciones meteorológicas estables.</i>
  `;
}

function searchCity() {
  const q = document.getElementById('inputCitySearch').value.trim();
  if (!q) return;
  currentCity = q;
  localStorage.setItem('cfg_city', currentCity);
  document.getElementById('currentCityLabel').innerText = `📍 ${currentCity}`;
  closeModal('modalCity');
  refreshData();
}
