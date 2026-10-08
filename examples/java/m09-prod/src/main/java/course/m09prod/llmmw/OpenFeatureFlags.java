package course.m09prod.llmmw;

import course.m09prod.llmmw.Flags.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Value;

public final class OpenFeatureFlags {
    private OpenFeatureFlags() {}

    /**
     * Тот же выбор варианта через OpenFeature: процент, таргетинг и kill switch живут в сервисе
     * флагов (flagd, Unleash, LaunchDarkly и др.), код от вендора не зависит.
     */
    public static Variant variant(Client c, String key, String tenant, String user, Variant def) {
        try {
            var d = c.getObjectDetails(key, new Value(), new MutableContext(user).add("tenant", tenant));
            if (d.getErrorCode() != null || !d.getValue().isStructure()) {
                return def; // флага нет или сервис флагов недоступен — работаем на проверенном варианте
            }
            // Провайдеры отдают объект как Structure из JSON.
            Variant out = Flags.JSON.convertValue(d.getValue().asStructure().asObjectMap(), Variant.class);
            return out.model() == null || out.model().isEmpty() ? def : out;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
