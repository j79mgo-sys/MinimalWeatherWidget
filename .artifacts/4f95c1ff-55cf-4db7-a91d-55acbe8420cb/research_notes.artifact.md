# Arquitectura de Proveedores Meteorológicos e Hidrológicos (RIA, SAIH, Embalses y AEMET/Open-Meteo)

## Análisis Técnico Profesional

1. **Naturaleza de los Datos**:
   - **AEMET y Open-Meteo**: Son proveedores de **predicción meteorológica** (forecast a 7 días, temperaturas, viento, lluvia prevista).
   - **RIA (IFAPA) y SAIH (Hidrosur / Guadalquivir)**: Son redes de **observación y telecontrol en tiempo real** (estaciones agroclimáticas, pluviómetros horarios y estado de embalses en las cuencas andaluzas).

2. **Estrategia de Integración (Patrón Strategy + Enriquecimiento)**:
   - **Predicción**: El usuario puede seleccionar entre **AEMET** y **Open-Meteo** como motor principal de predicción, con fallback automático (AEMET -> Open-Meteo).
   - **Observación e Hidrología**: Se añade una nueva sección de **Estado de Embalses y Datos Hidrológicos de Andalucía / Cuenca** utilizando fuentes abiertas de la CHG / MITECO / SAIH.

3. **Experiencia de Usuario**:
   - Selector de proveedor en los ajustes de la app.
   - Tarjeta informativa de **Embalses** con porcentaje de agua embalsada y capacidad total.
