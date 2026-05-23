package com.prince.turtlekeyboard.ui.mcp;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.prince.ai.McpClient;
import com.prince.kbd.core.KeyValueStore;
import com.prince.kbd.core.McpService;
import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.integration.usermcp.McpBinding;
import com.prince.turtlekeyboard.integration.usermcp.McpErrorMessages;
import com.prince.turtlekeyboard.settings.Prefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Three-step add flow (Endpoint → Pick tool → Name binding) for a user MCP binding.
 * Bindings are read-only once saved; delete and recreate to change.
 */
public class McpAddBindingActivity extends AppCompatActivity {

    private final McpService mcp = new McpClient();

    private LinearLayout stepEndpoint;
    private LinearLayout stepPickTool;
    private LinearLayout stepName;

    private EditText editEndpoint;
    private EditText editToken;
    private TextView discoverStatus;
    private Button btnDiscover;

    private RadioGroup toolsGroup;

    private EditText editCommand;
    private EditText editLabel;
    private EditText editEmoji;
    private EditText editArgs;
    private EditText editResultFormat;

    /** Cached tools/list response so step 3 can prefill the schema hint. */
    private JSONArray discoveredTools;
    private String selectedToolName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_add_binding);

        stepEndpoint = findViewById(R.id.step_endpoint);
        stepPickTool = findViewById(R.id.step_pick_tool);
        stepName = findViewById(R.id.step_name);

        editEndpoint = findViewById(R.id.edit_endpoint);
        editToken = findViewById(R.id.edit_token);
        discoverStatus = findViewById(R.id.discover_status);
        btnDiscover = findViewById(R.id.btn_discover);
        btnDiscover.setOnClickListener(v -> onDiscover());

        toolsGroup = findViewById(R.id.tools_group);
        findViewById(R.id.btn_use_tool).setOnClickListener(v -> onUseTool());

        editCommand = findViewById(R.id.edit_command);
        editLabel = findViewById(R.id.edit_label);
        editEmoji = findViewById(R.id.edit_emoji);
        editArgs = findViewById(R.id.edit_args);
        editResultFormat = findViewById(R.id.edit_result_format);
        findViewById(R.id.btn_save).setOnClickListener(v -> onSave());
    }

    // -- Step 1: discover ----------------------------------------------------

    private void onDiscover() {
        String endpoint = editEndpoint.getText().toString().trim();
        if (!endpoint.startsWith("https://")) {
            Toast.makeText(this, "Endpoint must be https://", Toast.LENGTH_SHORT).show();
            return;
        }
        String token = editToken.getText().toString().trim();
        if (token.isEmpty()) token = null;

        btnDiscover.setEnabled(false);
        discoverStatus.setVisibility(View.VISIBLE);
        discoverStatus.setText("Discovering tools…");

        mcp.tools(endpoint, token, new McpService.ToolsCallback() {
            @Override public void onTools(JSONArray tools) {
                btnDiscover.setEnabled(true);
                if (tools.length() == 0) {
                    discoverStatus.setText("Server returned no tools.");
                    return;
                }
                discoverStatus.setVisibility(View.GONE);
                discoveredTools = tools;
                populateToolsGroup(tools);
                stepPickTool.setVisibility(View.VISIBLE);
            }
            @Override public void onError(String reason) {
                btnDiscover.setEnabled(true);
                discoverStatus.setText(McpErrorMessages.userMessage(reason, "Discovery"));
            }
        });
    }

    private void populateToolsGroup(JSONArray tools) {
        toolsGroup.removeAllViews();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            String name = t.optString("name", "");
            String desc = t.optString("description", "");
            if (name.isEmpty()) continue;
            RadioButton rb = new RadioButton(this);
            rb.setId(View.generateViewId());
            rb.setText(desc.isEmpty() ? name : name + " — " + desc);
            rb.setTag(i);
            toolsGroup.addView(rb);
        }
        if (toolsGroup.getChildCount() > 0) {
            ((RadioButton) toolsGroup.getChildAt(0)).setChecked(true);
        }
    }

    // -- Step 2: pick --------------------------------------------------------

    private void onUseTool() {
        int checked = toolsGroup.getCheckedRadioButtonId();
        if (checked == -1) {
            Toast.makeText(this, "Pick a tool first", Toast.LENGTH_SHORT).show();
            return;
        }
        RadioButton rb = findViewById(checked);
        int idx = (Integer) rb.getTag();
        JSONObject t = discoveredTools.optJSONObject(idx);
        if (t == null) return;

        String name = t.optString("name", "");
        selectedToolName = name;
        editCommand.setText(name);
        editLabel.setText(name);
        // Prefill arg template from the tool's input schema; user can edit before saving.
        editArgs.setText(buildArgTemplateHint(t.optJSONObject("inputSchema")));

        stepPickTool.setVisibility(View.GONE);
        stepName.setVisibility(View.VISIBLE);
    }

    private static String buildArgTemplateHint(JSONObject inputSchema) {
        if (inputSchema == null) return "{}";
        JSONObject props = inputSchema.optJSONObject("properties");
        if (props == null) return "{}";
        JSONObject out = new JSONObject();
        java.util.Iterator<String> it = props.keys();
        boolean firstStringField = true;
        while (it.hasNext()) {
            String k = it.next();
            try {
                // First string field gets ${prompt}; the rest get type-appropriate defaults.
                JSONObject p = props.optJSONObject(k);
                String type = p == null ? "string" : p.optString("type", "string");
                if ("string".equals(type) && firstStringField) {
                    out.put(k, "${prompt}");
                    firstStringField = false;
                } else if ("string".equals(type)) {
                    out.put(k, "");
                } else if ("number".equals(type) || "integer".equals(type)) {
                    out.put(k, 0);
                } else if ("boolean".equals(type)) {
                    out.put(k, false);
                } else {
                    out.put(k, JSONObject.NULL);
                }
            } catch (JSONException ignored) { }
        }
        try {
            return out.toString(2);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    // -- Step 3: save --------------------------------------------------------

    private void onSave() {
        String command = editCommand.getText().toString().trim().toLowerCase();
        if (TextUtils.isEmpty(command) || !command.matches("[a-z][a-z0-9-]*")) {
            Toast.makeText(this,
                    "Command must be lowercase letters / digits / hyphens, start with a letter",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String label = editLabel.getText().toString().trim();
        if (label.isEmpty()) label = command;
        String emoji = editEmoji.getText().toString().trim();
        if (emoji.isEmpty()) emoji = "🔌";

        String argsRaw = editArgs.getText().toString().trim();
        JSONObject args;
        try {
            args = argsRaw.isEmpty() ? new JSONObject() : new JSONObject(argsRaw);
        } catch (JSONException e) {
            Toast.makeText(this, "Argument template is not valid JSON: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Refuse to override a built-in or another user binding.
        KeyValueStore store = new Prefs(this).root().scoped("user-mcp");
        for (McpBinding existing : McpBinding.loadAll(store)) {
            if (existing.command.equals(command)) {
                Toast.makeText(this, "/" + command + " is already bound — remove it first",
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (isReservedCommand(command)) {
            Toast.makeText(this, "/" + command + " is a built-in command",
                    Toast.LENGTH_LONG).show();
            return;
        }

        McpBinding binding = new McpBinding(
                "b_" + UUID.randomUUID().toString().substring(0, 8),
                command, label, emoji,
                editEndpoint.getText().toString().trim(),
                selectedToolName,
                args,
                editResultFormat.getText().toString().trim(),
                System.currentTimeMillis());

        List<McpBinding> all = new ArrayList<>(McpBinding.loadAll(store));
        all.add(binding);
        McpBinding.saveAll(store, all);

        String token = editToken.getText().toString().trim();
        if (!token.isEmpty()) {
            store.putString(McpBinding.tokenKey(binding.id), token);
        }

        Toast.makeText(this, "/" + command + " bound — restart keyboard session to use",
                Toast.LENGTH_LONG).show();
        finish();
    }

    /** Hardcoded reservations for built-in command names. */
    private static boolean isReservedCommand(String name) {
        switch (name) {
            case "split": case "splits":
            case "notion": case "slack":
            case "web": case "us":
            case "poll": case "wyr":
            case "history":
            case "cap": case "sticker": case "edit": case "style":
            case "fix": case "tone": case "reply": case "tl":
                return true;
            default:
                return false;
        }
    }
}
