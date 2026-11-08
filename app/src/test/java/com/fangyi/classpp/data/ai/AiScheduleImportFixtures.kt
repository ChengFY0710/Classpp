package com.fangyi.classpp.data.ai

/**
 * AI 识别导入测试夹具：一份能通过 `ShareCodec.decodeForImport` 全量校验的最小分享 JSON
 * （18 周学期、两节作息、一门周三第 1 节的课）。
 */
internal const val AI_SCHEDULE_IMPORT_VALID_JSON: String = """{
  "formatVersion": 1,
  "kind": "classpp.schedule",
  "exportedAtMillis": 1792250752103,
  "schedule": {
    "id": "s-2026-2027-1",
    "name": "2026-2027 第1学期",
    "termStart": "2026-08-31",
    "termEnd": "2027-01-03",
    "daysPerWeek": 7,
    "slots": [
      { "startTime": "8:20", "endTime": "10:00" },
      { "startTime": "10:20", "endTime": "12:00" }
    ],
    "slotDurationMinutes": 100,
    "courses": [
      {
        "id": "c01",
        "name": "组织胚胎学",
        "teacher": "张三",
        "location": "黄家湖 J202",
        "dayOfWeek": 3,
        "startSlot": 1,
        "span": 1,
        "weeks": { "segments": [ { "start": 1, "end": 18, "parity": "ALL" } ] },
        "color": "Pink",
        "note": ""
      }
    ],
    "showInactiveCourses": true
  }
}"""

/** 同上但没有课程：解析/校验通过，仓库层据此返回 `NoCourses` */
internal const val AI_SCHEDULE_IMPORT_EMPTY_JSON: String = """{
  "formatVersion": 1,
  "kind": "classpp.schedule",
  "exportedAtMillis": 1792250752103,
  "schedule": {
    "id": "s-empty",
    "name": "空课表",
    "termStart": "2026-08-31",
    "termEnd": "2027-01-03",
    "daysPerWeek": 7,
    "slots": [
      { "startTime": "8:20", "endTime": "10:00" }
    ],
    "slotDurationMinutes": 100,
    "courses": [],
    "showInactiveCourses": true
  }
}"""
