package io.github.codecodegogogo.HyperosAutoTaskPlus.inject;

import io.github.codecodegogogo.HyperosAutoTaskPlus.FirstAppKeys;

import java.io.Serializable;

/** Stores a native action without asking Gson to deserialize an abstract TaskItem. */
public final class ThenAction implements Serializable {
    private static final long serialVersionUID = 1L;

    private String key;
    private String json;

    private ThenAction() {}

    public ThenAction(String key, String json) {
        if (!validKey(key) || !validJson(json)) throw new IllegalArgumentException("执行项目配置无效");
        this.key = key;
        this.json = json;
    }

    public String getKey() { return key == null ? "" : key; }
    public String getJson() { return json == null ? "" : json; }

    /** Payload parsing and native parameter validation belong to ThenActionCodec. */
    public boolean isValid() { return validKey(key) && validJson(json); }

    public ThenAction copy() {
        ThenAction result = new ThenAction();
        result.key = key;
        result.json = json;
        return result;
    }

    private static boolean validKey(String key) {
        return key != null && key.length() > "_result_item".length()
                && key.endsWith("_result_item") && key.equals(key.trim())
                && !FirstAppKeys.KEY_THEN_EXECUTION_RESULT.equals(key);
    }

    private static boolean validJson(String json) {
        return json != null && !json.trim().isEmpty() && !"null".equals(json.trim());
    }
}
