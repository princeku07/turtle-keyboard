package com.prince.turtlekeyboard.ui.mcp;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.prince.kbd.core.KeyValueStore;
import com.prince.turtlekeyboard.R;
import com.prince.turtlekeyboard.integration.usermcp.McpBinding;
import com.prince.turtlekeyboard.settings.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists the user's MCP bindings (one row per binding, tap to delete) and launches
 * {@link McpAddBindingActivity} for the add flow. List rebuilds on every {@code onResume}
 * so a freshly-saved binding shows up without an explicit refresh.
 */
public class McpServersActivity extends AppCompatActivity {

    private KeyValueStore store;
    private LinearLayout container;
    private TextView emptyLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mcp_servers);
        store = new Prefs(this).root().scoped("user-mcp");
        container = findViewById(R.id.bindings_container);
        emptyLabel = findViewById(R.id.empty_label);
        Button addBtn = findViewById(R.id.btn_add_binding);
        addBtn.setOnClickListener(v ->
                startActivity(new Intent(this, McpAddBindingActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderBindings();
    }

    private void renderBindings() {
        container.removeAllViews();
        List<McpBinding> bindings = McpBinding.loadAll(store);
        emptyLabel.setVisibility(bindings.isEmpty() ? View.VISIBLE : View.GONE);
        for (McpBinding b : bindings) {
            container.addView(buildRow(b));
        }
    }

    private View buildRow(McpBinding b) {
        // Plain LinearLayout row: emoji + "/<command>  — label" + delete affordance via
        // long-press dialog. Keeps the screen dependency-free (no RecyclerView) while
        // staying readable for the v1 scope.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(b.emoji + "  /" + b.command + "   " + b.label);
        title.setTextSize(16);

        TextView sub = new TextView(this);
        sub.setText(b.tool + "  •  " + b.endpoint);
        sub.setTextSize(12);
        sub.setAlpha(0.6f);

        row.addView(title);
        row.addView(sub);

        row.setOnClickListener(v -> confirmDelete(b));
        return row;
    }

    private void confirmDelete(McpBinding b) {
        new AlertDialog.Builder(this)
                .setTitle("Remove /" + b.command + "?")
                .setMessage("This unbinds " + b.tool + " on " + b.endpoint
                        + " and deletes its stored bearer token.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    deleteBinding(b);
                    Toast.makeText(this, "Removed /" + b.command, Toast.LENGTH_SHORT).show();
                    renderBindings();
                })
                .show();
    }

    private void deleteBinding(McpBinding target) {
        List<McpBinding> remaining = new ArrayList<>();
        for (McpBinding b : McpBinding.loadAll(store)) {
            if (!b.id.equals(target.id)) remaining.add(b);
        }
        McpBinding.saveAll(store, remaining);
        // Nuke the token under its dedicated key so credentials don't outlive the binding.
        store.putString(McpBinding.tokenKey(target.id), "");
    }
}
