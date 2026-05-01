/**
 * Turtle Keyboard — Split cloud sync backend.
 *
 * Deploy:
 *   1. Open https://script.google.com → New project → paste this file as Code.gs.
 *   2. File → Project settings → Script properties:
 *        SHEET_ID      = the target Google Sheet ID (from its URL)
 *        SHARED_TOKEN  = any random string; paste the same value into the
 *                        Android client's SplitKeys.CLOUD_TOKEN.
 *   3. Deploy → New deployment → type "Web app".
 *        Execute as: me
 *        Who has access: Anyone
 *        Copy the /exec URL into SplitKeys.CLOUD_ENDPOINT on the device.
 *   4. The first POST will create a "Splits" tab and header row automatically.
 *
 * Sheet schema (one row per save):
 *   timestampIso | timestampMs | deviceId | amount | people | perPerson
 */

const SHEET_NAME = 'Splits';
const HEADERS = ['timestampIso', 'timestampMs', 'deviceId', 'amount', 'people', 'perPerson'];

function doGet(e) {
  try {
    const props = PropertiesService.getScriptProperties();
    const expected = props.getProperty('SHARED_TOKEN') || '';
    const params = (e && e.parameter) || {};
    const action = params.action || 'debug';

    if (expected && params.token !== expected && action !== 'debug') {
      return json_({ ok: false, error: 'unauthorized' });
    }

    const sheet = getOrCreateSheet_(props.getProperty('SHEET_ID'));

    if (action === 'list') {
      const last = sheet.getLastRow();
      const rows = [];
      if (last > 1) {
        const values = sheet.getRange(2, 1, last - 1, HEADERS.length).getValues();
        for (const r of values) {
          rows.push({
            timestampMs: Number(r[1]) || 0,
            deviceId: String(r[2] || ''),
            amount: Number(r[3]) || 0,
            people: Number(r[4]) || 0,
          });
        }
      }
      return json_({ ok: true, rows: rows });
    }

    // Default: debug payload
    const ss = sheet.getParent();
    return json_({
      ok: true,
      sheetId: props.getProperty('SHEET_ID') || '(unset — using bound spreadsheet)',
      spreadsheetUrl: ss.getUrl(),
      spreadsheetName: ss.getName(),
      tab: sheet.getName(),
      rowCount: Math.max(0, sheet.getLastRow() - 1),
      tokenConfigured: !!expected,
    });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    const props = PropertiesService.getScriptProperties();
    const expected = props.getProperty('SHARED_TOKEN') || '';
    if (expected && body.token !== expected) {
      return json_({ ok: false, error: 'unauthorized' });
    }

    const sheet = getOrCreateSheet_(props.getProperty('SHEET_ID'));

    if (body.action === 'save') {
      const amount = Number(body.amount) || 0;
      const people = Number(body.people) || 1;
      const ts = Number(body.timestampMs) || Date.now();
      sheet.appendRow([
        new Date(ts).toISOString(),
        ts,
        String(body.deviceId || ''),
        amount,
        people,
        people > 0 ? amount / people : amount,
      ]);
      return json_({ ok: true });
    }

    if (body.action === 'clear') {
      // Soft-clear: remove this device's rows only, so other devices keep theirs.
      const deviceId = String(body.deviceId || '');
      const last = sheet.getLastRow();
      if (last > 1 && deviceId) {
        const range = sheet.getRange(2, 1, last - 1, HEADERS.length);
        const values = range.getValues();
        const kept = values.filter(r => String(r[2]) !== deviceId);
        sheet.getRange(2, 1, last - 1, HEADERS.length).clearContent();
        if (kept.length > 0) {
          sheet.getRange(2, 1, kept.length, HEADERS.length).setValues(kept);
        }
      }
      return json_({ ok: true });
    }

    return json_({ ok: false, error: 'unknown action' });
  } catch (err) {
    return json_({ ok: false, error: String(err) });
  }
}

function getOrCreateSheet_(sheetId) {
  const ss = sheetId ? SpreadsheetApp.openById(sheetId) : SpreadsheetApp.getActive();
  let sheet = ss.getSheetByName(SHEET_NAME);
  if (!sheet) {
    sheet = ss.insertSheet(SHEET_NAME);
    sheet.appendRow(HEADERS);
  } else if (sheet.getLastRow() === 0) {
    sheet.appendRow(HEADERS);
  }
  return sheet;
}

function json_(obj) {
  return ContentService
      .createTextOutput(JSON.stringify(obj))
      .setMimeType(ContentService.MimeType.JSON);
}
