package dev.kizuna.meridian.piglinsword;

import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.StrikeShape;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueCatalog;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueDefinition;
import dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.TechniqueEffect;
import dev.kizuna.meridian.technique.server.TechniqueCatalogManifest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class PiglinSwordCatalogTest {
    private static final String NAMESPACE = "meridian_piglin_sword";
    private static final String CATALOG_ENTRY = "data/meridian_piglin_sword/technique_catalog.txtpb";
    private static final String BOOK_ID = NAMESPACE + ":molten_sword";
    private static final String VOLUME_ID = NAMESPACE + ":upper";
    private static final String WIND_ID = NAMESPACE + ":wind_around";
    private static final String QUIVER_ID = NAMESPACE + ":quiver";
    private static final String SPRING_BACK_ID = NAMESPACE + ":spring_back";
    private static final double EPSILON = 0.000001D;
    private static final int SPLASH_WARMUP_MILLIS = 600;
    private static final double SPLASH_DAMAGE_RATIO = 1.5D;

    @Test
    public void productionJarContainsOnlyTheP0DataCatalog() throws Exception {
        // 正式安装包按元数据发现目录，并确认纯数据包没有执行类。
        try (JarFile jar = new JarFile(System.getProperty("piglinSword.fabricJar"))) {
            var metadata = jar.getJarEntry("fabric.mod.json");
            assertNotNull(metadata);
            String json = new String(jar.getInputStream(metadata).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains(NAMESPACE));
            assertTrue(json.contains(CATALOG_ENTRY));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().endsWith(".class")));
            var catalogEntry = jar.getJarEntry(CATALOG_ENTRY);
            assertNotNull(catalogEntry);
            String text = new String(jar.getInputStream(catalogEntry).readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(catalog(), TechniqueCatalogManifest.parseText(NAMESPACE + ":" + CATALOG_ENTRY, text));
        }
    }

    @Test
    public void catalogListsBothVolumesInLearningOrder() throws Exception {
        // 上卷暴露流金剑三式和三档书级持物规则。
        TechniqueCatalog catalog = catalog();
        assertEquals(1, catalog.getBooksCount());
        assertEquals(2, catalog.getVolumesCount());
        assertEquals(6, catalog.getDefinitionsCount());
        var lower = catalog.getVolumes(1);
        assertEquals(List.of(VOLUME_ID), lower.getPrerequisiteVolumeIdsList());
        var forms = lower.getTechniquesList().stream().map(reference -> reference.getId()).toList();
        assertEquals(List.of(NAMESPACE + ":coil", NAMESPACE + ":molten_flow", NAMESPACE + ":gold_splash"), forms);
        var book = catalog.getBooks(0);
        assertEquals(BOOK_ID, book.getId());
        assertEquals(3, book.getHeldItemRules().getCategoriesCount());
        assertEquals(3, book.getHeldItemRules().getDurabilityCount());
        assertEquals(List.of("soft_blades", "swords", "other_items"), book.getHeldItemRules()
                .getCategoriesList().stream().map(category -> category.getId()).toList());
        assertEquals(VOLUME_ID, catalog.getVolumes(0).getId());
        assertEquals(List.of(WIND_ID, QUIVER_ID, SPRING_BACK_ID), catalog.getVolumes(0).getTechniquesList().stream()
                .map(reference -> reference.getId()).toList());
    }

    @Test
    public void windAroundIsAOneTargetShieldBypassingFinalStrike() throws Exception {
        // 绕是一记单体收尾击，显式绑定主副手绕盾伤害类型。
        TechniqueDefinition definition = definition(catalog(), WIND_ID);
        assertEquals(NAMESPACE + ":piglin_sword_wind_around", presentationId(definition));
        assertEquals(350, definition.getPhases().getWarmupMillis());
        assertEquals(3000, definition.getCooldownMillis());
        var strike = weaponStrikes(definition).get(0);
        assertEquals(1.0D, strike.getMultiplier().getValue(), EPSILON);
        assertEquals(StrikeShape.SINGLE_TARGET, strike.getShape());
        assertEquals(1, strike.getMaximumTargets());
        assertEquals(1, strike.getCastTargetLimit());
        assertTrue(strike.getLastStrike());
        assertEquals(1, strike.getDurabilityBase());
        assertEquals(NAMESPACE + ":wind_around_main_hand", strike.getMainHandDamageTypeId());
        assertEquals(NAMESPACE + ":wind_around_off_hand", strike.getOffHandDamageTypeId());
    }

    @Test
    public void quiverHasThreeIndependentHitsAndOnlyTheLastConsumesDurability() throws Exception {
        // 抖在总时长内三次独立命中，只有末击负责一次耐久结算。
        TechniqueDefinition definition = definition(catalog(), QUIVER_ID);
        assertEquals(300, definition.getPhases().getWarmupMillis());
        assertEquals(200, definition.getPhases().getActiveMillis());
        assertEquals(350, definition.getPhases().getRecoveryMillis());
        assertEquals(NAMESPACE + ":piglin_sword_quiver", presentationId(definition));
        assertEquals(5000, definition.getCooldownMillis());
        var effects = weaponStrikes(definition);
        assertEquals(List.of(300L, 400L, 500L), definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasWeaponStrike).map(TechniqueEffect::getAtMillis).toList());
        assertEquals(List.of(0.4D, 0.4D, 0.4D), effects.stream()
                .map(strike -> strike.getMultiplier().getValue()).toList());
        assertEquals(3, effects.stream().map(strike -> strike.getDedupGroup()).distinct().count());
        assertFalse(effects.get(0).getLastStrike());
        assertFalse(effects.get(1).getLastStrike());
        assertTrue(effects.get(2).getLastStrike());
        assertEquals(1, effects.get(2).getDurabilityBase());
    }

    @Test
    public void requiredSlotTagsAndSoftBladeTagArePresentInTheJar() throws Exception {
        // 槽位和物品标签是玩家绑定与金剑选择的固定数据入口。
        TechniqueDefinition wind = definition(catalog(), WIND_ID);
        assertTrue(wind.getSlotsList().stream()
                .anyMatch(slot -> slot.getTagsList().contains("human:hand_meridian")));
        assertTrue(wind.getSlotsList().stream()
                .anyMatch(slot -> slot.getTagsList().contains("human:arm_muscle")));
        try (JarFile jar = new JarFile(System.getProperty("piglinSword.fabricJar"))) {
            var softBlades = jar.getJarEntry("data/meridian_piglin_sword/tags/item/soft_blades.json");
            assertNotNull(softBlades);
            String softBladeText = new String(jar.getInputStream(softBlades).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(softBladeText.contains("minecraft:golden_sword"));
            var shieldBypass = jar.getJarEntry("data/minecraft/tags/damage_type/bypasses_shield.json");
            assertNotNull(shieldBypass);
            String shieldText = new String(jar.getInputStream(shieldBypass).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(shieldText.contains("wind_around_main_hand"));
        }
    }

    @Test
    public void springBackIsAForwardSingleTargetFollowUp() throws Exception {
        // 弹只承接已记录的阻挡事实，沿施放者正面完成一次末击。
        TechniqueDefinition definition = definition(catalog(), SPRING_BACK_ID);
        assertEquals(NAMESPACE + ":piglin_sword_spring_back", presentationId(definition));
        assertTrue(definition.getFollowUp().getOnlyFollowUp());
        assertEquals(List.of(WIND_ID, QUIVER_ID, ""), definition.getFollowUp().getTransitionsList().stream()
                .map(transition -> transition.getPrevious().getId()).toList());
        assertTrue(definition.getFollowUp().getTransitionsList().stream()
                .allMatch(transition -> transition.getBlockedAttackSourcesCount() == 3));
        var strike = weaponStrikes(definition).get(0);
        assertEquals(1.6D, strike.getMultiplier().getValue(), EPSILON);
        assertEquals(0.0D, strike.getFacingOffset().getValue(), EPSILON);
        assertEquals(StrikeShape.SINGLE_TARGET, strike.getShape());
        assertTrue(strike.getLastStrike());
        assertEquals(1, strike.getDurabilityBase());
        assertEquals(NAMESPACE + ":spring_back_main_hand", strike.getMainHandDamageTypeId());
        assertEquals(NAMESPACE + ":spring_back_off_hand", strike.getOffHandDamageTypeId());
        assertEquals(0, presentationAtMillis(definition));
    }

    @Test
    public void splashReleasesOnTheAnimationStrikeFrame() throws Exception {
        // 动作起点、剑身消失与局部热伤须共享正式目录的释放时刻。
        var splash = definition(catalog(), NAMESPACE + ":gold_splash");
        assertEquals(NAMESPACE + ":piglin_sword_gold_splash", presentationId(splash));
        assertEquals(0, presentationAtMillis(splash));
        assertEquals(SPLASH_WARMUP_MILLIS, splash.getPhases().getWarmupMillis());
        var hits = splash.getEffectsList().stream().filter(TechniqueEffect::hasWeaponStrike).toList();
        assertEquals(1, hits.size());
        var hit = hits.getFirst();
        assertEquals(SPLASH_WARMUP_MILLIS, hit.getAtMillis());
        var strike = hit.getWeaponStrike();
        assertEquals(StrikeShape.SECTOR, strike.getShape());
        assertEquals(SPLASH_DAMAGE_RATIO, strike.getMultiplier().getValue(), EPSILON);
    }

    private static TechniqueCatalog catalog() throws IOException {
        // 单元验证与正式发现共用功法库的有界文本解析器。
        try (var stream = PiglinSwordCatalogTest.class.getResourceAsStream("/" + CATALOG_ENTRY)) {
            if (stream == null) {
                throw new IOException("找不到流金剑目录资源：" + CATALOG_ENTRY);
            }
            byte[] bytes = stream.readNBytes(TechniqueCatalogManifest.MAX_BYTES + 1);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return TechniqueCatalogManifest.parseText(NAMESPACE + ":" + CATALOG_ENTRY, text);
        }
    }

    private static TechniqueDefinition definition(TechniqueCatalog catalog, String id) {
        // 每个断言从唯一目录源读取对应版本，避免手写第二套事实。
        return catalog.getDefinitionsList().stream()
                .filter(candidate -> candidate.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到流金剑式：" + id));
    }

    private static List<dev.kizuna.meridian.technique.protocol.pb.TechniqueMessages.WeaponStrike> weaponStrikes(
            TechniqueDefinition definition) {
        // 只提取武器打击，保持目录声明顺序供断言复用。
        return definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasWeaponStrike)
                .map(TechniqueEffect::getWeaponStrike)
                .toList();
    }

    private static String presentationId(TechniqueDefinition definition) {
        return definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasPresentation)
                .map(effect -> effect.getPresentation().getId())
                .findFirst()
                .orElseThrow();
    }

    private static long presentationAtMillis(TechniqueDefinition definition) {
        return definition.getEffectsList().stream()
                .filter(TechniqueEffect::hasPresentation)
                .mapToLong(TechniqueEffect::getAtMillis)
                .findFirst()
                .orElseThrow();
    }
}
