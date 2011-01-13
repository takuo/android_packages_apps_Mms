/*
 * Copyright (C) 2010 Takuo Kitame.
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mms.util;

import android.content.Context;
// import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;
import android.text.TextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmojiConverter {
    private static final float DEFAULT_SPAN_SCALE = 0.625f;
    private static EmojiConverter sInstance;
    public static EmojiConverter getInstance() { return sInstance; }
    private final Context mContext;
    private final Pattern mPattern;

    public static void init(Context context) {
        sInstance = new EmojiConverter(context);
    }

    private EmojiConverter(Context context) {
        mContext = context;
        mPattern = buildPattern();
    }

    private Pattern buildPattern() {
        StringBuilder patternString = new StringBuilder();

        patternString.append('(');
        for ( int cp = 0xE001; cp <= 0xE53E; cp++) {
            if ((cp > 0xE05A && cp < 0xE101) ||
                (cp > 0xE15A && cp < 0xE201) ||
                (cp > 0xE25A && cp < 0xE301) ||
                (cp > 0xE34D && cp < 0xE401) ||
                (cp > 0xE44C && cp < 0xE501))
                    continue;
            patternString.appendCodePoint(cp);
            patternString.append('|');
        }

        // Replace the extra '|' with a ')'
        patternString.replace(patternString.length() - 1, patternString.length(), ")");

        return Pattern.compile(patternString.toString());
    }

    public ImageSpan getSpanCodePoint(int codePoint) {
        return getSpanCodePoint(codePoint, DEFAULT_SPAN_SCALE);  
    }

    public ImageSpan getSpanCodePoint(int codePoint, float scale) {
        String s = "emoji_" + Integer.toHexString(codePoint);
        int resId = mContext.getResources().getIdentifier(s, "drawable", mContext.getPackageName());
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        Bitmap bmp  = BitmapFactory.decodeResource(mContext.getResources(), resId);
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(),
                                  matrix, true);
        return new ImageSpan(mContext, bmp);
    }

    public CharSequence addEmojiSpans(CharSequence text) {
        return addEmojiSpans(text, DEFAULT_SPAN_SCALE);
    }

    public CharSequence addEmojiSpans(CharSequence text, float scale) {
        if (TextUtils.isEmpty(text)) return text;
        SpannableStringBuilder builder = new SpannableStringBuilder(text);

        Matcher matcher = mPattern.matcher(text);
        while (matcher.find()) {
            ImageSpan span = getSpanCodePoint(matcher.group().codePointAt(0), scale);
            builder.setSpan(span, matcher.start(), matcher.end(),
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

}

