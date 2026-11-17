# Documentação Técnica e Operacional: Port Nativo de Rage Racer para Android

> **Versão:** 1.0.0 — Produção  
> **Arquitetura Alvo:** Android ARM64 (`arm64-v8a`), API 26+ (Testado e homologado no Android 15/16, API 36)  
> **Dispositivo de Homologação:** Infinix X6852 (MediaTek Dimensity / Mali GPU)  
> **Repositório Público:** [https://github.com/NunesFilipe/rage-racer-android](https://github.com/NunesFilipe/rage-racer-android)  
> **Data de Fechamento:** Novembro de 2026  

---

## 1. Visão Geral e Arquitetura do Sistema

O projeto consiste na compilação e adaptação **100% nativa** do código-fonte do clássico **Rage Racer (PlayStation 1, Namco, 1996)** para o sistema operacional Android, sem a utilização de emuladores (como DuckStation, ePSXe ou RetroArch).

```
+-------------------------------------------------------------------------+
|                          Camada Android (Java)                          |
|  - RageRacerActivity (SDLActivity + SensorEventListener)                |
|  - Injeção de Controles Virtuais Touch (Arcade HUD)                    |
|  - Leitura do Vetor de Gravidade de Hardware (Sensor.TYPE_GRAVITY)      |
|  - Gerenciamento de Orientação Imersiva (WindowInsetsController)        |
+-------------------------------------------------------------------------+
                                    |
                            JNI / libSDL3.so
                                    v
+-------------------------------------------------------------------------+
|                  Camada Nativa C17 / C++20 (libmain.so)                 |
|  - PSY-Z Runtime: Reimplementação moderna das bibliotecas PSY-Q da Sony  |
|  - SDL_GPU / Renderizador Vulkan 1.0+ (VSync 60 FPS travado)            |
|  - Áudio de Baixa Latência (AAudio / Oboe / SDL Audio CD-DA)            |
|  - Leitor de Imagem de Disco CHD (Track 01 Dados + 16 Faixas de Áudio)   |
|  - Simulação de Física e Carroceria (car_body_roll.c)                   |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                           Hardware do Celular                           |
|  - GPU Mali / Adreno via Drivers Vulkan Nativos                         |
|  - Acelerômetro / Sensor de Gravidade Fused a 100Hz                     |
|  - Display 120Hz/60Hz com Recorte de Câmera (DisplayCutout ShortEdges)  |
+-------------------------------------------------------------------------+
```

### 1.1 Por que Port Nativo e não Emulação?

| Dimensão | Emulação Clássica (RetroArch / DuckStation) | Port Nativo C17 (Este Projeto) |
|---|---|---|
| **Execução de Instruções** | Tradução dinâmica (JIT) ou interpretação de MIPS R3000A | Compilação direta AArch64 (ARM64-v8a) nativa |
| **Pipeline Gráfico** | Emulação de registradores do GPU PS1 em OpenGL/Vulkan | Renderizador nativo Vulkan com chamadas diretas de GPU |
| **Latência de Entrada** | 3 a 5 quadros de atraso devido ao buffer de emulação | Resposta de entrada imediata (< 10ms) |
| **Controle por Inclinação** | Emulação de analógico com zonas mortas virtuais confusas | Projeção trigonométrica pura no vetor de gravidade real |
| **Consumo de Bateria** | Alto aquecimento e drenagem rápida de bateria | Consumo reduzido equivalente a um app Android nativo leve |

---

## 2. Engenharia da Direção por Inclinação (Giroscópio / Gravidade)

A direção por inclinação foi totalmente reprojetada para fornecer uma experiência estilo *Real Racing 3*, eliminando atrasos e falhas de ângulo.

### 2.1 O Problema dos Sensores Convencionais

1. **Acelerômetro Cru (`Sensor.TYPE_ACCELEROMETER`):** Mede a aceleração total (gravidade + movimento das mãos). Em curvas rápidas, a aceleração linear gerada pelo movimento do usuário gera ruído no sinal, sacudindo a direção.
2. **Ângulos de Euler (`SensorManager.getOrientation`):** O cálculo tradicional de *roll* e *pitch* projeta a rotação em uma esfera de Euler. Quando o usuário segura o celular deitado em uma posição confortável de jogo ($\approx 30^\circ$ a $45^\circ$ em relação à mesa), o *roll* medido sofre distorção trigonométrica proporcional ao seno do *pitch*, forçando o jogador a erguer o aparelho na vertical para conseguir virar.

### 2.2 A Solução Matemática: Projeção Vetorial no Plano da Tela

Utiliza-se o **`Sensor.TYPE_GRAVITY`** (sensor fundido de gravidade do Android, que remove a aceleração linear dinâmica e isola a aceleração da gravidade estática $g = 9.81 \, \text{m/s}^2$).

Ao segurar o dispositivo em modo paisagem (`Surface.ROTATION_90` ou `ROTATION_270`):
* O eixo $X$ do sensor corre ao longo do topo da tela.
* O eixo $Y$ do sensor corre ao longo da lateral esquerda da tela.

A gravidade decomposta em relação à rotação do volante ($\text{steer}$) e inclinação da tela ($\text{pitch}$) é dada por:

$$g_x = g \cdot \cos(\text{pitch}) \cdot \cos(\text{steer})$$

$$g_y = g \cdot \cos(\text{pitch}) \cdot \sin(\text{steer})$$

Ao calcular o arco-tangente de dois argumentos:

$$\theta = \text{atan2}(g_y, g_x) \implies \frac{g_y}{g_x} = \frac{\sin(\text{steer})}{\cos(\text{steer})} = \tan(\text{steer})$$

> **Resultado:** O termo $\cos(\text{pitch})$ é cancelado na divisão. Isso significa que o ângulo de direção calculado ($\theta$) é **matematicamente invariante à inclinação com que o jogador segura o celular**. O volante funciona de maneira idêntica se jogado a $15^\circ$, $40^\circ$ ou $75^\circ$.

```java
// Implementação em RageRacerActivity.java
float angleDeg;
float screenUp;
if (rotation == Surface.ROTATION_270) {
    angleDeg = (float) Math.toDegrees(Math.atan2(-gy, -gx));
    screenUp = -gx;
} else {
    angleDeg = (float) Math.toDegrees(Math.atan2(gy, gx));
    screenUp = gx;
}

int newDirection = 0;
float tiltMagnitude = (float) Math.hypot(gx, gy);

// Zona morta calibrada em 4.5 graus
if (tiltMagnitude > 1.5f && screenUp > 0.0f) {
    if (angleDeg > STEER_DEADZONE_DEG) {
        newDirection = 1;  // Direita (PAD_RIGHT)
    } else if (angleDeg < -STEER_DEADZONE_DEG) {
        newDirection = -1; // Esquerda (PAD_LEFT)
    }
}
```

### 2.3 Resolução do Conflito com a Física Interna do Jogo

No código nativo do jogo (`rage-racer-pc/src/main/PAL/main/car/car_body_roll.c`), a função de direção digital opera da seguinte maneira:

```c
static void UpdateDigitalSteering(PlayerCarRuntime *car) {
    GameCarDrive *drive = &car->drive;
    u16 held = g_PadHeld;
    s32 steerPosition = drive->steerPos;

    if (held & g_PadButtonMapping[0]) {
        // Incrementa o esterçamento em passos digitais de 1536 unidades
        drive->steerPos = steerPosition - DIGITAL_STEERING_STEP;
    } else {
        // Quando solto, divide o esterçamento por 3 A CADA FRAME
        drive->steerPos /= DIGITAL_STEERING_RELEASE_DIVISOR; // (Divisor = 3)
    }
}
```

* **Causa do travamento anterior:** Uma implementação anterior tentava modular o ângulo ligando e desligando o direcional em pulsos de 10ms (PWM). Como o jogo divide o ângulo por 3 a cada frame sem botão pressionado, a direção caía para 0 instantaneamente, congelando o volante.
* **Correção:** O estado digital é mantido continuamente pressionado enquanto o celular estiver inclinado além de $4.5^\circ$. O acumulador do jogo atinge o esterçamento pleno em exatamente 3 frames (~50ms), resultando em uma curva suave, responsiva e sem engasgos.

---

## 3. Interface de Toque e Mapeamento de Controles

### 3.1 Botões Virtuais na Tela (Arcade HUD)
* **Design translúcido neon:** Posicionados nos cantos extremos inferiores da tela para manter o centro e as pistas de corrida 100% desobstruídos.
* **Canto Esquerdo:** D-Pad digital direcional e botão seletor de opacidade (`40%`, `15%`, `0%` / invisível).
* **Canto Superior Esquerdo:** Botão `[ 🔄 GIRO: ON / OFF ]` para alternar o modo volante por inclinação em tempo real.
* **Canto Direito:** Botões PlayStation estilizados:
  * $\times$ (Cruz): Aceleração
  * $\square$ (Quadrado): Freio / Ré
  * $\triangle$ (Triângulo): Mudança de câmera / Cancelar nos menus
  * $\bigcirc$ (Círculo): Ação auxiliar / Menu
  * `L1 / R1`: Espelhos retrovisores
  * `L2 / R2`: Câmbio manual (reduzir / subir marcha)
  * `SELECT` e `START`: Pausa e configurações

### 3.2 Suporte a Controles Físicos (Bluetooth / USB)
O motor SDL3 detecta automaticamente qualquer controle conectado (DualSense, DualShock 4, Xbox Series/One, 8BitDo) via HID nativo do Android. O jogador pode conectar o controle e jogar imediatamente sem qualquer configuração manual, podendo zerar a opacidade dos botões na tela para tela cheia limpa.

---

## 4. Empacotamento do APK e Separação Legal de Repositório

### 4.1 APK Autossuficiente Local (`RageRacer-Android.apk`)
* **Tamanho final:** ~509 MB.
* **Conteúdo:**
  * Binários compilados ARM64: `lib/arm64-v8a/libmain.so` e `libSDL3.so`.
  * Shaders Vulkan compilados em SPIR-V.
  * Imagem de disco completa embutida em `assets/Rage Racer.chd` (dados NTSC-U + 16 faixas de áudio CD-DA originais).
* **Comportamento no primeiro boot:** O app detecta a imagem CHD em seus assets internos, inicializa o leitor virtual de CD-ROM nativo e executa diretamente, sem que o usuário precise transferir arquivos para `/sdcard/`.

### 4.2 Repositório Git Público
* **URL:** `https://github.com/NunesFilipe/rage-racer-android`
* **Conformidade de Direitos Autorais:** De acordo com as normas de preservação e direitos autorais, o repositório público hospeda **exclusivamente o código-fonte C17**, as ferramentas de compilação, o harness Android e a documentação.
* **Arquivos Bloqueados via `.gitignore`:**
  * Imagens de disco (`*.chd`, `*.bin`, `*.cue`, `*.iso`).
  * Pacotes compilados pesados (`*.apk`, `*.aab`).
  * Caches de build (`build/`, `.cxx/`, `.gradle/`).
  * Saves de memory card (`bu00/`, `bu10/`).
* **Tratamento de Segredos (`.gitleaksignore`):**
  * Configurado para suprimir 16 falsos-positivos detectados pelo `gitleaks` em bibliotecas terceiras de baixo nível (como registradores AVX-512 da biblioteca `zstd`, constantes de criptografia STM32 e tabelas de teclas da `imgui`).

---

## 5. Mapeamento da Plataforma Station-Z

Durante o desenvolvimento, foi realizada a pesquisa completa para publicação e integração do port no aplicativo da plataforma **Station-Z** (projeto associado ao canal Znacker e desenvolvido por Belmante).

### 5.1 Fatos e Estrutura Técnica do App Station-Z
* **Pacote Android:** `com.stationz` (versão 0.6.1 instalada e inspecionada no aparelho).
* **Backend:** Supabase (`https://zuzodifmaolcmzycfnmq.supabase.co`).
* **Modelo de Catálogo:** O Station-Z é um catálogo curado. Não existe uma interface web aberta de auto-publicação ("developer portal") onde usuários avulsos possam subir APKs sem aprovação direta dos idealizadores.
* **Repositório do projeto:** `BelmanteGu/stationz-app`.

### 5.2 Canais Oficiais de Contato para Submissão Futura
Caso o usuário deseje submeter o jogo para o catálogo da Station-Z futuramente, os canais diretos e oficiais são:

1. **WhatsApp Oficial de Suporte do Station-Z:**
   * **Número:** `+55 (67) 98133-0339`
   * **Acesso:** Disponível diretamente dentro do app Station-Z (Menu lateral $\rightarrow$ botão *"Falar com o suporte"*).
2. **Instagram Oficial do Canal Znacker:**
   * **Perfil:** `@znacker77`
3. **Esclarecimento sobre o Discord:** O servidor de Discord inicialmente encontrado na internet pertencia a uma comunidade internacional de recompilação (com canais em inglês/espanhol para *Persona 5*, *NieR*, etc.), não tendo vínculo com a curadoria brasileira da Station-Z.

---

## 6. Guia Operacional de Manutenção e Compilação

### 6.1 Pré-requisitos
* Android SDK 35 (Platform Tools, Build Tools 35.0.0).
* Android NDK `27.1.12297006` (ou superior).
* Java JDK 17+.
* CMake 3.22.1+.

### 6.2 Comandos de Compilação e Deploy

```bash
# 1. Compilar o APK em modo Debug
cd "/mnt/SSD/Port de Jogos/Games PS1/Rage Racer/android"
./gradlew assembleDebug

# 2. Instalar diretamente no dispositivo conectado via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Executar o jogo no aparelho
adb shell am start -n org.libsdl.app/.RageRacerActivity

# 4. Monitorar logs do renderizador Vulkan e sensores
adb logcat -s RageRacer:V SDL:V Vulkan:V
```

### 6.3 Estrutura de Diretórios do Projeto

```
.
├── android/                   # Harness do projeto Android (Gradle + Java JNI)
│   ├── app/
│   │   ├── build.gradle       # Configurações de compilação NDK e SDK
│   │   └── src/main/java/     # RageRacerActivity.java (Sensores, UI, Imersão)
│   └── build.gradle
├── docs/                      # Documentações de arquitetura e design
│   ├── DOCUMENTACAO_DO_PORT.md # Este documento
│   └── superpowers/specs/     # Especificações técnicas de módulos
├── rage-racer-pc/             # Código-fonte descompilado do jogo em C17
│   ├── src/main/              # Lógica de física, renderização e áudio
│   ├── external/psyz/         # Camada PSY-Z (emulação das APIs Sony PS1)
│   └── CMakeLists.txt         # Configuração de build nativo ARM64
├── screenshots/               # Galeria de capturas reais em 60 FPS
├── .gitignore                 # Filtro de conformidade de direitos autorais
├── .gitleaksignore            # Isenção de falsos positivos de segurança
├── LICENSE.md                 # Licença de código aberto
├── README.md                  # Documentação em Inglês
└── README.pt-BR.md            # Documentação em Português
```
