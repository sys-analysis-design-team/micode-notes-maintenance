/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.tool.AiSettings;

/**
 * M6：AI 接口配置页。
 *
 * <p>Key 只写入应用私有 SharedPreferences；界面不提供导出，也不写入便签数据库。</p>
 */
public class AiSettingsActivity extends Activity {
    private EditText mEndpoint;
    private EditText mModel;
    private EditText mApiKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ai_settings);
        setTitle(R.string.ai_settings_title);

        mEndpoint = (EditText) findViewById(R.id.et_ai_endpoint);
        mModel = (EditText) findViewById(R.id.et_ai_model);
        mApiKey = (EditText) findViewById(R.id.et_ai_key);

        AiSettings settings = AiSettings.load(this);
        mEndpoint.setText(settings.getEndpoint());
        mModel.setText(settings.getModel());
        mApiKey.setText(settings.getApiKey());

        Button save = (Button) findViewById(R.id.btn_ai_save);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveSettings();
            }
        });
        Button clear = (Button) findViewById(R.id.btn_ai_clear);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mApiKey.setText("");
                AiSettings.clearApiKey(AiSettingsActivity.this);
                Toast.makeText(AiSettingsActivity.this, R.string.ai_settings_key_cleared,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveSettings() {
        String endpoint = mEndpoint.getText().toString().trim();
        String model = mModel.getText().toString().trim();
        String key = mApiKey.getText().toString().trim();
        boolean validEndpoint = endpoint.startsWith("https://") || endpoint.startsWith("http://");
        if (!validEndpoint || TextUtils.isEmpty(model) || TextUtils.isEmpty(key)) {
            Toast.makeText(this, R.string.ai_settings_invalid, Toast.LENGTH_LONG).show();
            return;
        }
        new AiSettings(endpoint, model, key).save(this);
        Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
