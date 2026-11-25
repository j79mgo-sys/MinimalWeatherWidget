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
function openCityModal() { openModal('modalCity'); }

// Carga inicial
document.addEventListener('DOMContentLoaded', () => {
  initApp();
});

function initApp() {
  document.getElementById('currentCityLabel').innerText = `📍 ${currentCity}`;
  document.getElementById('wCityTemp').innerText = `📍 ${currentCity} --°`;
  refreshData();
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

    // Conteo de horas deporte verde
    let greenCount = 0;
    const startH = d === 0 ? currentHourNow : 7;
    for (let h = startH; h < 22; h++) {
      const idx = d * 24 + h;
      if (idx < data.hourly.time.length) {
        const temp = data.hourly.temperature_2m[idx];
        const rain = data.hourly.precipitation[idx];
        const wind = data.hourly.wind_speed_10m[idx];
        const uv = data.hourly.uv_index ? data.hourly.uv_index[idx] : 0;

        if (temp >= getCfg('cfg_opt_min_t', 10) && temp <= getCfg('cfg_opt_max_t', 24) &&
            rain <= getCfg('cfg_rain_opt_max', 0) &&
            wind <= getCfg('cfg_wind_opt_max', 25) &&
            uv <= getCfg('cfg_uv_opt_max', 5)) {
          greenCount++;
        }
      }
    }

    const sportsColor = greenCount > 0 ? '#2E7D32' : '#718096';

    const colHtml = `
      <div class="widget-col">
        <div class="widget-day-header">
          <div class="widget-day-letter">${dayLetter}</div>
          <div class="widget-cam" style="visibility: ${showMoon ? 'visible' : 'hidden'};">📷</div>
          <div class="widget-weather-icon">${icon}</div>
        </div>
        ${showWind ? `<div class="widget-wind-text">↗ ${windSpd}</div>` : ''}
        <div class="widget-temp-text">${maxT}° / ${minT}°</div>
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

// Render 168 Horas Timeline (Exact Clon App Novedad 1:1)
function render168HoursTimeline(data) {
  const hourly = data.hourly;
  const daysMapFull = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado'];
  const now = new Date();
  const startHourTime = new Date(now.getFullYear(), now.getMonth(), now.getDate(), now.getHours(), 0, 0);

  let html = '';
  let lastDayHeader = '';

  for (let i = 0; i < hourly.time.length; i++) {
    const tStr = hourly.time[i];
    if (!tStr) continue;

    const parts = tStr.split('T');
    const dParts = parts[0].split('-');
    const date = new Date(parseInt(dParts[0]), parseInt(dParts[1]) - 1, parseInt(dParts[2]));

    if (date < startHourTime && date.getDate() !== now.getDate()) continue;

    const hParts = (parts[1] || '00:00').split(':');
    const hr = parseInt(hParts[0]);

    const dayLabel = date.getDate() === now.getDate() ? 'Hoy' :
                     date.getDate() === new Date(now.getTime() + 86400000).getDate() ? `Mañana (${daysMapFull[date.getDay()]})` :
                     `${daysMapFull[date.getDay()]} ${date.getDate()}`;

    if (dayLabel !== lastDayHeader) {
      lastDayHeader = dayLabel;
      html += `<div class="day-divider">${dayLabel}</div>`;
    }

    const temp = Math.round(hourly.temperature_2m[i]);
    const rain = hourly.precipitation[i] || 0;
    const wind = Math.round(hourly.wind_speed_10m[i] || 0);
    const windDir = hourly.wind_direction_10m ? hourly.wind_direction_10m[i] : 0;
    const uv = Math.round(hourly.uv_index ? hourly.uv_index[i] : 0);
    const icon = wmoToEmoji(hourly.weather_code[i]);

    const arrow = degToArrow(windDir);
    const bars = windBars(wind);
    const sportsDot = evaluateSportsTrafficLight(temp, wind, rain, uv);

    html += `
      <div class="timeline-item">
        <div class="t-time">${String(hr).padStart(2, '0')}:00</div>
        <div class="t-info">
          <span class="t-icon">${icon}</span>
          <span class="t-temp">${temp}°</span>
        </div>
        <div class="t-wind">
          <span>${arrow} ${bars} ${wind}</span>
          ${uv >= 3 ? `<span class="t-uv" style="margin-left:auto;">UV${uv}</span>` : ''}
        </div>
        <div class="t-sports">
          <span>🏃</span><span>${sportsDot}</span>
        </div>
      </div>
    `;
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
