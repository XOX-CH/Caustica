# DLSS 级联调节系统设计文档

## 概述

在 Caustica 路径追踪渲染管线中插入 5 个可独立 0-100% 数值调节的滑块控件，形成从光源到最终输出的级联调节链路：

```
太阳光色温(🌞) → GI间接光照(💡) → Bloom曝光(🌟) → 色调偏移(🎨) + 饱和度(🎚️)
```

每个调节项以简单滑块形式出现在「视频设置 → 光线追踪」菜单中，数值范围 0-100%，每个步骤的输出作为下一步的输入，形成自然的级联效果。

## 1. 调节项定义

| 编号 | 调节项 | 滑块范围 | 0% | 50% | 100% | 默认值 |
|------|--------|---------|----|-----|------|-------|
| 1 | 太阳光色温 | 0~100 | 冷色(10000K) | 物理天空色 | 暖色(3000K) | 50 |
| 2 | GI间接光照 | 0~100 | 无间接光(仅直射) | 50%间接强度 | 全物理GI | 100 |
| 3 | Bloom曝光 | 0~100 | 无Bloom | 50%强度 | 全Bloom(默认) | 100 |
| 4 | 色调偏移 | 0~100 | -60°色相旋转 | 无偏移 | +60°色相旋转 | 50 |
| 5 | 饱和度 | 0~100 | 灰度(0.0x) | 原始饱和度(1.0x) | 2x饱和度增强 | 50 |

## 2. 配置组织结构

### 2.1 新增配置组

```java
// CausticaConfig.java 新增

// === 光照控制 ===
public static final class Lighting {
    // 太阳光色温：0.0=冷, 0.5=物理天空色, 1.0=暖
    public static final FloatSetting SUN_COLOR_TEMP =
        clampedFloat("caustica.rt.lighting.sunColorTemp", "lighting.sun-color-temp", 0.5f, 0.0f, 1.0f);
}

// === Bloom 用户控制 ===
public static final class Bloom {
    // Bloom 强度乘数：0.0=无, 1.0=全量(使用look.json默认值)
    public static final FloatSetting STRENGTH =
        clampedFloat("caustica.rt.bloom.strength", "bloom.strength", 1.0f, 0.0f, 1.0f);
}

// === Rt.Composite 新增 ===
// GI间接光照强度：0.0=无, 1.0=全物理
public static final FloatSetting GI_STRENGTH =
    clampedFloat("caustica.rt.composite.giStrength", "composite.gi-strength", 1.0f, 0.0f, 1.0f);

// === Rt.Tonemap 新增 ===
// 色调偏移：0.0=-60°, 0.5=0°, 1.0=+60°
public static final FloatSetting HUE_SHIFT =
    clampedFloat("caustica.rt.tonemap.hueShift", "tonemap.hue-shift", 0.5f, 0.0f, 1.0f);
// 饱和度：0.0=灰度, 0.5=原始, 1.0=2x增强
public static final FloatSetting SATURATION =
    clampedFloat("caustica.rt.tonemap.saturation", "tonemap.saturation", 0.5f, 0.0f, 1.0f);
```

### 2.2 TOML 配置映射

```toml
[lighting]
sun-color-temp = 0.5

[bloom]
strength = 1.0

[composite]
gi-strength = 1.0

[tonemap]
hue-shift = 0.5
saturation = 0.5
```

## 3. 着色器变更

### 3.1 Sun 色温 — `world_common.slang`

**WorldPush 新增字段：**

```slang
public struct WorldPush {
    // ... 现有字段 ...
    public float4   skyLook3;    // x ground albedo, y horizon soften, z roughness scale, w reflection scale
    public float4   skyLook4;    // NEW: x sunColorTemp, y giStrength, z (unused), w (unused)
    // ... 其余字段 ...
};
```

`skyLook4.x` = sunColorTemp (0.0=冷, 0.5=中性, 1.0=暖)
`skyLook4.y` = giStrength (0.0=无GI, 1.0=全物理)

### 3.2 色温曲线 — `sky.slang`

在 `dominantCelestialLight()` 返回之后，对 `celestialLight.illuminance` 应用色温曲线：

```slang
// 在 skyState() 或 dominantCelestialLight() 中应用色温
float tempFactor = worldPush.skyLook4.x; // 0.0-1.0

// 色温颜色曲线：RGB 乘数
// 冷色(0.0) = 蓝紫偏移, 中性(0.5) = 物理色(无变化), 暖色(1.0) = 橙红偏移
float3 tempColor;
if (tempFactor < 0.5) {
    // 冷色 → 中性
    float t = tempFactor * 2.0; // 0.0 → 1.0
    tempColor = lerp(float3(1.15, 0.95, 0.85), float3(1.0, 1.0, 1.0), t);
} else {
    // 中性 → 暖色
    float t = (tempFactor - 0.5) * 2.0; // 0.0 → 1.0
    tempColor = lerp(float3(1.0, 1.0, 1.0), float3(1.0, 0.75, 0.45), t);
}

celestialLight.illuminance *= tempColor;
```

### 3.3 GI间接光照 — `indirect.rgen.slang`

在 `tracePath()` 函数中，分离直射/间接贡献：

```slang
// tracePath() 返回后，对间接部分应用 GI 强度
float3 L = tracePath(...); // 已累积的总路径辐射
float giStrength = worldPush.skyLook4.y; // 0.0-1.0

// 关于直射/间接分离的方案：
// 方案 A（推荐）：在 tracePath 中跟踪 directContribution
// 方案 B（简单）：直接缩放整体 L，但会改变直射光
// 采用方案 A，在 tracePath 内部：

float3 tracePath(..., out float3 directContribution) {
    float3 L = float3(0.0);
    float3 directL = float3(0.0);
    bool firstBounce = true;

    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        float3 bounceContrib = float3(0.0);

        // ... 现有光线追踪逻辑，累积到 bounceContrib ...

        L += bounceContrib;
        if (firstBounce) {
            directL += bounceContrib;
            firstBounce = false;
        }
    }

    directContribution = directL;
    return L;
}

// 调用处：
float3 directL;
float3 totalL = tracePath(..., directL);
float3 indirectL = totalL - directL;
float3 finalL = directL + indirectL * giStrength;
```

### 3.4 Bloom 曝光 — `RtComposite.java`

在 `recordFrame()` 中，将 bloom 强度乘以用户控制值：

```java
// 现有代码：
float bloomStrength = LOOK.bloom().strength() / bloomLevels.length;

// 改为：
float userBloomStrength = CausticaConfig.Rt.Bloom.STRENGTH.value(); // 0.0-1.0
float bloomStrength = LOOK.bloom().strength() * userBloomStrength / bloomLevels.length;
```

### 3.5 色调偏移 + 饱和度 — `display_common.slang` 和 `display/main.comp.slang`

**DisplayPush 新增字段：**

```slang
public struct DisplayPush {
    // ... 现有字段 ...
    public float   bloomStrength;
    public float   hueShift;     // NEW: -1.0 到 1.0, 映射到 -60° 到 +60°
    public float   saturation;   // NEW: 0.0 到 2.0, 0=灰度, 1=原始, 2=2x增强
    public float   _pad;         // 对齐填充
};
```

**Java 端映射：**

```java
// 0-100% → 着色器值
float hueShift = (CausticaConfig.Rt.Tonemap.HUE_SHIFT.value() - 0.5f) * 2.0f; // -1.0 ~ 1.0
float saturation = CausticaConfig.Rt.Tonemap.SATURATION.value() * 2.0f; // 0.0 ~ 2.0
```

**显示着色器中的色调饱和度处理**（在 tonemap 之后）：

```slang
// 色调映射后
float3 displayColor = tonemap(lookedAcesCg);

// 1. 饱和度调整
float luminance = dot(displayColor, float3(0.2126, 0.7152, 0.0722));
float3 saturated = lerp(float3(luminance), displayColor, pc.saturation);

// 2. 色调偏移（在 RGB 空间中做色相旋转）
float3 hueShifted = applyHueShift(saturated, pc.hueShift);

// 输出
outputImage[pix] = float4(hueShifted, 1.0);
```

**色相旋转函数**（使用标准 RGB 色相旋转矩阵）：

```slang
// hueShift: -1.0 到 1.0, 对应 -60° 到 +60°
float3 applyHueShift(float3 color, float hueShift) {
    float angle = hueShift * 60.0 * (3.14159265 / 180.0); // 弧度
    float s = sin(angle);
    float c = cos(angle);

    // 色相旋转矩阵 (YIQ 空间)
    float3x3 hueRot = float3x3(
        0.299 + 0.701 * c + 0.168 * s, 0.587 - 0.587 * c + 0.330 * s, 0.114 - 0.114 * c - 0.497 * s,
        0.299 - 0.299 * c - 0.328 * s, 0.587 + 0.413 * c + 0.035 * s, 0.114 - 0.114 * c + 0.292 * s,
        0.299 - 0.300 * c + 1.250 * s, 0.587 - 0.588 * c - 1.050 * s, 0.114 + 0.886 * c - 0.203 * s
    );

    return mul(hueRot, color);
}
```

## 4. Java 端变更

### 4.1 RtVideoOptions.java — 新增 5 个滑块

```java
// === 太阳光色温 ===
private static OptionInstance<Integer> sunColorTemp() {
    FloatSetting setting = CausticaConfig.Rt.Lighting.SUN_COLOR_TEMP;
    return new OptionInstance<>(
        "caustica.options.rt.sunColorTemp",
        OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.sunColorTemp.tooltip")),
        (caption, pct) -> Options.genericValueLabel(caption,
                Component.literal(pct + "%")),
        new OptionInstance.IntRange(0, 100),
        Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
        pct -> setting.set(pct / 100.0f));
}

// === GI间接光照 ===
private static OptionInstance<Integer> giStrength() {
    FloatSetting setting = CausticaConfig.Rt.Composite.GI_STRENGTH;
    return new OptionInstance<>(
        "caustica.options.rt.giStrength",
        OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.giStrength.tooltip")),
        (caption, pct) -> Options.genericValueLabel(caption,
                Component.literal(pct + "%")),
        new OptionInstance.IntRange(0, 100),
        Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
        pct -> setting.set(pct / 100.0f));
}

// === Bloom曝光 ===
private static OptionInstance<Integer> bloomStrength() {
    FloatSetting setting = CausticaConfig.Rt.Bloom.STRENGTH;
    return new OptionInstance<>(
        "caustica.options.rt.bloomStrength",
        OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.bloomStrength.tooltip")),
        (caption, pct) -> Options.genericValueLabel(caption,
                Component.literal(pct + "%")),
        new OptionInstance.IntRange(0, 100),
        Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
        pct -> setting.set(pct / 100.0f));
}

// === 色调偏移 ===
private static OptionInstance<Integer> hueShift() {
    FloatSetting setting = CausticaConfig.Rt.Tonemap.HUE_SHIFT;
    return new OptionInstance<>(
        "caustica.options.rt.hueShift",
        OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.hueShift.tooltip")),
        (caption, pct) -> {
            float degrees = (pct / 100.0f - 0.5f) * 120.0f; // -60° ~ +60°
            String sign = degrees > 0 ? "+" : "";
            return Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%s%.0f°", sign, degrees)));
        },
        new OptionInstance.IntRange(0, 100),
        Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
        pct -> setting.set(pct / 100.0f));
}

// === 饱和度 ===
private static OptionInstance<Integer> saturation() {
    FloatSetting setting = CausticaConfig.Rt.Tonemap.SATURATION;
    return new OptionInstance<>(
        "caustica.options.rt.saturation",
        OptionInstance.cachedConstantTooltip(Component.translatable("caustica.options.rt.saturation.tooltip")),
        (caption, pct) -> {
            float sat = pct / 100.0f; // 0.0 ~ 1.0
            return Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.0f%%", sat * 100.0f)));
        },
        new OptionInstance.IntRange(0, 100),
        Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
        pct -> setting.set(pct / 100.0f));
}
```

### 4.2 菜单布局

将 5 个新滑块放置在「光线追踪」设置分区中，按级联顺序排列在现有选项之后：

```
[现有选项...]
─────────────────────────────────
🌞 太阳光色温          [━━━●━━━] 50%
💡 GI间接光照          [━━━━━●━] 100%
🌟 Bloom曝光           [━━━━━●━] 100%
🎨 色调偏移            [━━━●━━━] 0°
🎚️ 饱和度              [━━━●━━━] 50%
─────────────────────────────────
[后续选项...]
```

### 4.3 RtComposite.java — 数据传递

**skyPush() 方法：** 将 `skyLook4` 写入

```java
private SkyPush skyPush() {
    // ... 现有代码 ...
    float sunColorTemp = CausticaConfig.Rt.Lighting.SUN_COLOR_TEMP.value();
    float giStrength = CausticaConfig.Rt.Composite.GI_STRENGTH.value();

    return new SkyPush(
        // ... 现有 Float4 参数 ...
        new Float4(sunAngle, moonAngle, starAngle, starBrightness),           // celestial
        new Float4(sunIlluminance, moonIlluminance, airglow, starLuminance),  // skyLook0
        new Float4(noonTilt, sunAngularRadius, moonAngularRadius, phaseFixed),// skyLook1
        new Float4(sunDiscHalfAngle, moonDiscHalfAngle, viewerAltitude, moonPhase), // skyLook2
        new Float4(groundAlbedo, horizonSoften, roughnessScale, reflectionScale),   // skyLook3
        new Float4(sunColorTemp, giStrength, 0.0f, 0.0f),                    // skyLook4 NEW
        sunUv, moonUv
    );
}
```

**Bloom 传递：** 在 `recordFrame()` 中

```java
float userBloomStrength = CausticaConfig.Rt.Bloom.STRENGTH.value();
float bloomStrength = LOOK.bloom().strength() * userBloomStrength / bloomLevels.length;
```

**Display 传递：** 在 `recordFrame()` 中

```java
float hueShift = CausticaConfig.Rt.Tonemap.HUE_SHIFT.value(); // 0.0-1.0
float saturation = CausticaConfig.Rt.Tonemap.SATURATION.value(); // 0.0-1.0
// 传递给 displayPipeline.dispatch() 的新参数
```

## 5. 本地化字符串

### en_us.json 新增

```json
{
  "caustica.options.rt.sunColorTemp": "Sun Color Temp",
  "caustica.options.rt.sunColorTemp.tooltip": "Adjust the color temperature of sunlight. Cool (0%) gives a blue tint, Neutral (50%) keeps the physical sky color, Warm (100%) gives an orange tint.",

  "caustica.options.rt.giStrength": "GI Indirect Lighting",
  "caustica.options.rt.giStrength.tooltip": "Controls the intensity of indirect light bounces. 0% = direct lighting only, 100% = full physical global illumination.",

  "caustica.options.rt.bloomStrength": "Bloom Exposure",
  "caustica.options.rt.bloomStrength.tooltip": "Controls the intensity of the bloom effect. 0% = no bloom, 100% = full bloom strength.",

  "caustica.options.rt.hueShift": "Hue Shift",
  "caustica.options.rt.hueShift.tooltip": "Rotates the hue of the final image. -60° to +60° range for fine color grading.",

  "caustica.options.rt.saturation": "Saturation",
  "caustica.options.rt.saturation.tooltip": "Controls color saturation. 0% = grayscale, 50% = original, 100% = 2x enhanced."
}
```

## 6. 实现清单

### 着色器（按顺序）
1. [ ] `world_common.slang` — WorldPush 新增 `skyLook4` 字段
2. [ ] `sky.slang` — 添加色温颜色曲线乘法
3. [ ] `indirect.rgen.slang` — 分离直射/间接贡献，应用 GI 强度
4. [ ] `display_common.slang` — DisplayPush 新增 `hueShift`, `saturation` 字段
5. [ ] `display/main.comp.slang` — 添加色调偏移和饱和度后处理

### Java
6. [ ] `CausticaConfig.java` — 新增 `Lighting`, `Bloom` 配置组，以及 `GI_STRENGTH`, `HUE_SHIFT`, `SATURATION`
7. [ ] `RtVideoOptions.java` — 新增 5 个滑块控件
8. [ ] `RtComposite.java` — 传递 `skyLook4`, 修改 bloom 强度计算, 传递显示参数
9. [ ] 更新 `DisplayPipeline.dispatch()` 签名以接受 hueShift/saturation

### 资源
10. [ ] `en_us.json` — 新增 5 组本地化键

### 构建
11. [ ] 重新运行 `generateShaderRecords` 任务以生成更新后的 `DisplayPushData`/`WorldPushData` Java 绑定类

## 7. 自检清单

- [ ] **无占位符：** 所有 TBD 或 TODO 已在上述设计中被替换为具体实现细节
- [ ] **内部一致性：** 配置项命名 (`caustica.rt.*`) 与现有模式一致；TOML 路径 (`lighting.*`, `bloom.*`) 与现有配置组命名风格一致
- [ ] **范围合理：** 这是一个独立的渲染调节功能，不涉及项目结构变更，适合单一实现计划
- [ ] **无歧义：** 0-100% 映射到具体物理/视觉意义；50% 中性点定义明确；级联关系清晰