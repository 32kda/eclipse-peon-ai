package org.sterl.llmpeon.skill;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillServiceContributedTest {

    @Test
    void catalogListsDescriptionOnly() {
        var service = new SkillService();
        service.setContributedSkills(List.of(
                SkillDefinition.of("contributed", "Does bundle work", "FULL BODY")));

        assertThat(service.getSkills()).hasSize(1);
        assertThat(service.getSkills().getFirst().getSource()).isEqualTo(SkillSource.EXTENSION);
        assertThat(service.catalog()).contains("- contributed [bundle]: Does bundle work");
        assertThat(service.catalog()).doesNotContain("FULL BODY");
    }

    @Test
    void configSkillOverridesContributedByName(@TempDir Path dir) throws Exception {
        var skillDir = Files.createDirectories(dir.resolve("contributed"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: contributed
                description: config version
                ---
                CONFIG BODY
                """);

        var service = new SkillService();
        service.setContributedSkills(List.of(
                SkillDefinition.of("contributed", "bundle version", "BUNDLE BODY")));
        service.refresh(dir);

        assertThat(service.get("contributed")).get()
                .satisfies(skill -> {
                    assertThat(skill.getSource()).isEqualTo(SkillSource.CONFIG);
                    assertThat(skill.getBody()).contains("CONFIG BODY");
                    assertThat(skill.getDescription()).isEqualTo("config version");
                });
    }

    @Test
    void disabledContributedSkillLeavesCatalog() {
        var service = new SkillService();
        service.setContributedSkills(List.of(
                SkillDefinition.of("contributed", "desc", "body")));

        service.setSkillEnabled("contributed", false);

        assertThat(service.catalog()).isEmpty();
        assertThat(service.getSkills()).isEmpty();
    }

    @Test
    void contributedSkillIsReadableThroughTheSkillModel() {
        var service = new SkillService();
        service.setContributedSkills(List.of(
                SkillDefinition.of("contributed", "desc", "FULL BODY")));

        assertThat(service.get("contributed").orElseThrow().renderBody()).contains("FULL BODY");
    }
}
