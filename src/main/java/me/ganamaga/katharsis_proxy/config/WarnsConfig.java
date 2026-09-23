package me.ganamaga.katharsis_proxy.config;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.util.ArrayList;
import java.util.List;

/** The {@code punishments.warns} section. */
@ConfigSerializable
public class WarnsConfig {

    /** How long a warning counts; {@code perm} for ever. */
    private String expireAfter = "30d";

    private List<EscalationStepConfig> escalation = new ArrayList<>();

    public String expireAfter() {
        return expireAfter == null || expireAfter.isBlank() ? "perm" : expireAfter;
    }

    public List<EscalationStepConfig> escalation() {
        return escalation == null ? List.of() : escalation;
    }
}
