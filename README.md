<div align="center">

<img src="https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white"/>
<img src="https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white"/>
<img src="https://img.shields.io/badge/Firebase-FFCA28?style=for-the-badge&logo=firebase&logoColor=black"/>

# 💰 LoanControl — App de Automatización de Préstamos

**Aplicación Android empresarial para el control integral de préstamos, cobranzas y clientes.**  
Desarrollada para [Capital Express](/) · Enero 2025

</div>

---

## 📋 Descripción

**MiniFinanciera** es una aplicación Android nativa desarrollada para automatizar la gestión de préstamos personales en empresas financieras. Elimina el control manual en hojas de cálculo y centraliza toda la operación de cobranza en tiempo real, con acceso diferenciado por rol de usuario.

La app permite registrar préstamos, generar tablas de amortización automáticas, enviar recordatorios de pago y consultar el historial completo de cada cliente desde cualquier dispositivo Android.

---

## ✨ Funcionalidades Principales

### 👤 Control de Clientes
- Registro completo de clientes con datos personales y de contacto
- Búsqueda y filtrado por nombre, estado de préstamo o cobrador asignado
- Historial individual de préstamos y pagos por cliente

### 💳 Gestión de Préstamos
- Creación de préstamos con monto, plazo, tasa de interés y fecha de inicio
- Generación automática de **tabla de amortización** (cuota fija, interés decreciente)
- Control de estado: activo, al día, en mora, finalizado
- Fecha de vencimiento de cada cuota calculada automáticamente

### 🔔 Notificaciones y Alertas
- **Notificaciones push** de recordatorio de pago días antes del vencimiento
- Alertas automáticas cuando un pago entra en mora
- Notificación al cobrador asignado al registrarse un pago nuevo

### 📊 Historial de Pagos
- Registro de cada pago con fecha, monto y cobrador responsable
- Detalle de cuotas pagadas vs. pendientes por préstamo
- Marca automática de cuotas como pagadas al registrar el pago

### 🛡️ Control de Roles
| Rol | Permisos |
|---|---|
| **Administrador** | Acceso total: clientes, préstamos, cobradores, reportes generales |
| **Cobrador** | Solo ve sus clientes asignados, registra pagos y consulta su reporte |

### 📈 Reportes
- Reporte por cobrador: total cobrado, pagos pendientes, clientes en mora
- Reporte general (solo administrador): cartera total, ingresos del período, mora global

---

## 🏗️ Arquitectura y Stack

```
LoanControl/
├── app/
│   ├── ui/
│   │   ├── login/          # Autenticación y control de roles
│   │   ├── dashboard/      # Dashboard diferenciado por rol
│   │   ├── clients/        # CRUD de clientes
│   │   ├── loans/          # Gestión de préstamos y amortización
│   │   ├── payments/       # Registro y historial de pagos
│   │   └── reports/        # Reportes por cobrador y generales
│   ├── data/
│   │   ├── model/          # Data classes: Client, Loan, Payment, User
│   │   └── repository/     # FirestoreRepository (CRUD + queries)
│   └── notifications/      # FCM + WorkManager para recordatorios
└── google-services.json
```

**Stack tecnológico:**

| Capa | Tecnología |
|---|---|
| Lenguaje | Kotlin |
| IDE | Android Studio |
| Base de datos | Firebase Firestore |
| Autenticación | Firebase Authentication |
| Notificaciones | Firebase Cloud Messaging (FCM) |
| Tareas en segundo plano | WorkManager |
| UI | XML Layouts + RecyclerView + Material Design |

---

## 📱 Capturas de Pantalla

> *(Agregar screenshots del Dashboard, tabla de amortización y registro de pagos)*

---

## ⚙️ Configuración e Instalación

### Prerrequisitos
- Android Studio Hedgehog o superior
- JDK 17+
- Dispositivo o emulador Android API 26+
- Cuenta Firebase con proyecto activo

### Pasos

```bash
# 1. Clonar el repositorio
git clone https://github.com/tuusuario/loancontrol-android.git
cd loancontrol-android

# 2. Agregar el archivo de configuración de Firebase
# Descarga google-services.json desde Firebase Console
# y colócalo en /app/google-services.json

# 3. Abrir en Android Studio y sincronizar Gradle
# File → Open → seleccionar la carpeta del proyecto

# 4. Ejecutar en dispositivo o emulador
# Run → Run 'app'
```

### Variables de configuración (Firebase)
En Firebase Console habilitar:
- ✅ Authentication (Email/Password)
- ✅ Firestore Database
- ✅ Cloud Messaging

---

## 🧮 Lógica de Amortización

La tabla de amortización se genera con el método de **cuota fija** (sistema francés):

```kotlin
fun calcularAmortizacion(monto: Double, tasaMensual: Double, plazoMeses: Int): List<Cuota> {
    val cuotaFija = monto * tasaMensual / (1 - (1 + tasaMensual).pow(-plazoMeses))
    val cuotas = mutableListOf<Cuota>()
    var saldo = monto

    for (mes in 1..plazoMeses) {
        val interes = saldo * tasaMensual
        val capital = cuotaFija - interes
        saldo -= capital
        cuotas.add(Cuota(mes, cuotaFija, capital, interes, saldo.coerceAtLeast(0.0)))
    }
    return cuotas
}
```

---

## 🔐 Modelo de Datos (Firestore)

```
users/{uid}
  ├── nombre: String
  ├── rol: "admin" | "cobrador"
  └── cobradorId: String?

clients/{clientId}
  ├── nombre, telefono, direccion
  └── cobradorAsignado: String

loans/{loanId}
  ├── clienteId, monto, tasa, plazoMeses
  ├── fechaInicio: Timestamp
  ├── estado: "activo" | "mora" | "finalizado"
  └── cuotas: [{ numero, fechaVencimiento, monto, pagado }]

payments/{paymentId}
  ├── loanId, clienteId, cobradorId
  ├── montoPagado, fechaPago: Timestamp
  └── cuotaNumero: Int
```

---

## 👨‍💻 Autor

**Carlos Daniell Castro Rodríguez**  
Desarrollador Android & Backend · Honduras  
📧 daniellcastro036@gmail.com  
📱 +504 3294-9219

---

## 📄 Licencia

Desarrollado para uso empresarial privado — Capital Express © 2025.  
Código disponible como portafolio profesional. No redistribuir sin autorización.
