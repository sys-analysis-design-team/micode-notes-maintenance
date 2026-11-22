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
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.tool.DataUtils;

import java.util.HashSet;

/**
 * 回收站：浏览已软删除的便签，支持恢复或彻底删除。
 */
public class TrashActivity extends Activity implements AdapterView.OnItemClickListener {

    private static final String TAG = "TrashActivity";

    private static final String[] PROJECTION = {
            NoteColumns.ID,
            NoteColumns.SNIPPET,
            NoteColumns.MODIFIED_DATE
    };

    private static final int COL_ID = 0;

    private static final int COL_SNIPPET = 1;

    private static final int COL_MODIFIED = 2;

    private static final int MENU_CLEAR_TRASH = 1;

    private ContentResolver mResolver;

    private ListView mListView;

    private TextView mEmptyView;

    private TrashAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.trash_list);
        mResolver = getContentResolver();
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.trash_title);
        }
        mListView = (ListView) findViewById(R.id.trash_list);
        mEmptyView = (TextView) findViewById(R.id.trash_empty);
        mAdapter = new TrashAdapter(this);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(this);
        loadTrash();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_CLEAR_TRASH, Menu.NONE, R.string.trash_clear);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == MENU_CLEAR_TRASH) {
            confirmClearTrash();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadTrash() {
        new AsyncTask<Void, Void, Cursor>() {

            @Override
            protected Cursor doInBackground(Void... unused) {
                return mResolver.query(Notes.CONTENT_NOTE_URI, PROJECTION,
                        NoteColumns.PARENT_ID + "=? AND " + NoteColumns.TYPE + "=?",
                        new String[] { String.valueOf(Notes.ID_TRASH_FOLER),
                                String.valueOf(Notes.TYPE_NOTE) },
                        NoteColumns.MODIFIED_DATE + " DESC");
            }

            @Override
            protected void onPostExecute(Cursor cursor) {
                mAdapter.swapCursor(cursor);
                boolean empty = cursor == null || cursor.getCount() == 0;
                mEmptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                mListView.setVisibility(empty ? View.GONE : View.VISIBLE);
            }

        }.execute();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        final Cursor cursor = mAdapter.getCursor();
        if (cursor == null || !cursor.moveToPosition(position)) {
            return;
        }
        final long noteId = cursor.getLong(COL_ID);
        String snippet = cursor.getString(COL_SNIPPET);
        if (TextUtils.isEmpty(snippet)) {
            snippet = getString(R.string.trash_untitled);
        }
        new AlertDialog.Builder(this)
                .setTitle(snippet)
                .setItems(new String[] { getString(R.string.trash_restore),
                        getString(R.string.trash_delete_forever) },
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (which == 0) {
                                    restore(noteId);
                                } else {
                                    confirmDelete(noteId);
                                }
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void restore(final long noteId) {
        new AsyncTask<Void, Void, Boolean>() {

            @Override
            protected Boolean doInBackground(Void... unused) {
                return DataUtils.restoreNoteFromTrash(mResolver, noteId);
            }

            @Override
            protected void onPostExecute(Boolean success) {
                Toast.makeText(TrashActivity.this, success ? R.string.trash_restored
                        : R.string.trash_restore_failed, Toast.LENGTH_SHORT).show();
                loadTrash();
            }

        }.execute();
    }

    private void confirmDelete(final long noteId) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_delete_confirm_title)
                .setMessage(R.string.trash_delete_confirm_message)
                .setPositiveButton(R.string.trash_delete_forever,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                deleteForever(noteId);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteForever(long noteId) {
        HashSet<Long> ids = new HashSet<Long>();
        ids.add(noteId);
        boolean success = DataUtils.batchDeleteNotes(mResolver, ids);
        Toast.makeText(this, success ? R.string.trash_deleted : R.string.trash_delete_failed,
                Toast.LENGTH_SHORT).show();
        loadTrash();
    }

    private void confirmClearTrash() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.trash_clear)
                .setMessage(R.string.trash_clear_confirm)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        clearTrash();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearTrash() {
        final ProgressDialog progress = ProgressDialog.show(this, null,
                getString(R.string.trash_clearing), true);
        new AsyncTask<Void, Void, Boolean>() {

            @Override
            protected Boolean doInBackground(Void... unused) {
                Cursor cursor = mResolver.query(Notes.CONTENT_NOTE_URI,
                        new String[] { NoteColumns.ID },
                        NoteColumns.PARENT_ID + "=? AND " + NoteColumns.TYPE + "=?",
                        new String[] { String.valueOf(Notes.ID_TRASH_FOLER),
                                String.valueOf(Notes.TYPE_NOTE) }, null);
                if (cursor == null) {
                    return false;
                }
                HashSet<Long> ids = new HashSet<Long>();
                try {
                    while (cursor.moveToNext()) {
                        ids.add(cursor.getLong(0));
                    }
                } finally {
                    cursor.close();
                }
                return DataUtils.batchDeleteNotes(mResolver, ids);
            }

            @Override
            protected void onPostExecute(Boolean success) {
                progress.dismiss();
                Toast.makeText(TrashActivity.this, success ? R.string.trash_cleared
                        : R.string.trash_delete_failed, Toast.LENGTH_SHORT).show();
                loadTrash();
            }

        }.execute();
    }

    @Override
    protected void onDestroy() {
        mAdapter.swapCursor(null);
        super.onDestroy();
    }

    private static final class TrashAdapter extends BaseAdapter {

        private final LayoutInflater mInflater;

        private final Context mContext;

        private Cursor mCursor;

        TrashAdapter(Context context) {
            mContext = context;
            mInflater = LayoutInflater.from(context);
        }

        Cursor getCursor() {
            return mCursor;
        }

        void swapCursor(Cursor cursor) {
            if (mCursor == cursor) {
                return;
            }
            if (mCursor != null) {
                mCursor.close();
            }
            mCursor = cursor;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mCursor == null ? 0 : mCursor.getCount();
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            if (mCursor == null || !mCursor.moveToPosition(position)) {
                return -1;
            }
            return mCursor.getLong(COL_ID);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = mInflater.inflate(R.layout.trash_item, parent, false);
            }
            TextView title = (TextView) view.findViewById(R.id.trash_item_title);
            TextView date = (TextView) view.findViewById(R.id.trash_item_date);
            if (mCursor != null && mCursor.moveToPosition(position)) {
                String snippet = mCursor.getString(COL_SNIPPET);
                title.setText(TextUtils.isEmpty(snippet)
                        ? mContext.getString(R.string.trash_untitled) : snippet);
                date.setText(DateFormat.format("yyyy-MM-dd HH:mm",
                        mCursor.getLong(COL_MODIFIED)));
            }
            return view;
        }
    }
}
