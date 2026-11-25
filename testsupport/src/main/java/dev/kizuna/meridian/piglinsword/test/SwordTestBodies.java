package dev.kizuna.meridian.piglinsword.test;

import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** 服务端用例和客户端拍摄从相同的部位伤势开始。 */
public final class SwordTestBodies {
    private SwordTestBodies() {
        // 所有状态属于当前测试世界。
    }

    public static <T extends Mob> T atHealth(T original, EntityType<T> type, double ratio) {
        // 伤势写入身体的部位存档，装备、学卷、真元和已释放记号随实体一起恢复。
        var snapshot = VanillaDamageFabric.inspectBody(original).orElseThrow().toBuilder();
        var maximum = snapshot.getStructure().getPartsList();
        for (var part : snapshot.getPartStatesBuilderList()) {
            var definition = maximum.stream().filter(entry -> entry.getId().equals(part.getId()))
                    .findFirst().orElseThrow();
            part.setHp(definition.getMaxHp() * ratio);
        }
        var registry = original.registryAccess();
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registry);
        original.saveWithoutId(output);
        var saved = output.buildResult();
        saved.getCompound("meridian_humanoid").orElseThrow().putByteArray("body", snapshot.build().toByteArray());
        var level = (ServerLevel) original.level();
        original.remove(RemovalReason.UNLOADED_TO_CHUNK);
        var restored = type.create(level, EntitySpawnReason.LOAD);
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, registry, saved));
        level.addFreshEntity(restored);
        return restored;
    }
}
