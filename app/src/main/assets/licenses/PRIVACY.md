# Privacy

Cassini for Android keeps your recordings, transcripts and voiceprints on your phone. Cassini does not upload them. Cassini has no accounts, no advertising, no analytics and no crash reports. The app connects to the internet only to download its models, and only after you agree.

This file applies to Cassini for Android 0.2 and later. The app shows the same text on its Licenses screen.

## The data that Cassini keeps

Cassini keeps your data in its private app storage (the `filesDir` directory of the app). Other apps cannot read this storage. Each note has its own directory, `notes/<noteId>/`. The `<noteId>` is a random identifier.

- Audio: your recordings and the audio files that you import or open with Cassini. Location: `notes/<noteId>/`. A recording is `capture.opus` or `capture.m4a`. Imported audio is `source.<ext>`. An imported Cassini document, or a note after transcription, is `document.opus`.
- Transcripts: the words and their times, the speaker labels, the speaker names that you give, and the title. Location: `notes/<noteId>/document.opus`. The catalogue database `cassini.db` keeps a copy of the text for the library and for search.
- Unfinished transcription: the progress of a transcription, the draft speaker names, the matches between speakers and saved voices, and short audio clips of each speaker (at most 45 seconds for each speaker). A match is the ID of a saved voice and a score. It is not a voiceprint. Location: `notes/<noteId>/work/`. Cassini deletes this directory when the transcription is complete, when you start again, or when it deletes the note.
- Voiceprints: a voiceprint is a list of numbers that Cassini calculates from the voice of one speaker. Cassini uses voiceprints to recognize the people that you name. Location: `voices/` only. The section "Voiceprints" below gives the details.
- Settings and state: your model download decision and its time, the queue of transcriptions, the trash state of notes, the playback position, and some one-time notices. Location: `settings.preferences_pb` and `cassini.db`. On Android 13 and later, Android keeps the app language. On earlier versions, Cassini keeps the app language in a small file in its private storage.
- Models: the downloaded speech and speaker models. They contain no personal data. Location: `parakeet-v3/`, `vad/` and `speaker-models/`.

When you import a file, or open a file with Cassini from a different app, Cassini copies the file into its storage. The original file stays where it is.

### Voiceprints

Cassini 0.2 keeps all voiceprints in the `voices/` directory, and nowhere else:

- `voices/people.json`: the saved voices. Each saved voice has a name, a voiceprint and its counts.
- `voices/notes/<noteId>.json`: the voiceprints of the speakers in one note, and their matches with the saved voices.
- `voices/pending/<noteId>.json`: the voiceprints of a transcription that is almost complete. When Cassini installs the result in the note, it moves them into `voices/notes/<noteId>.json`.
- `voices/generation`: a number that changes when you push "Forget everyone". It contains no voice data.

The database `cassini.db` also keeps the matches between speakers and saved voices: the ID of the saved voice and the score. An unfinished transcription keeps its matches in `notes/<noteId>/work/`. These matches are not voiceprints.

If you installed Cassini 0.2 over Cassini 0.1.x, the files of 0.1.x stay in the app storage: the recordings in `documents/`, `library.json`, and `voices.json` with the voiceprints of 0.1.x. Cassini 0.2 does not read these files and does not delete them. "Forget everyone" does not delete them. Only "Clear storage" in the Android app settings, or an uninstall, deletes them.

Cassini never writes voiceprints into a Cassini document or into an export. Only the names that you give go into these files.

### Audio that is not Opus

A Cassini document keeps its audio in the Opus format. Some audio is in a different format: for example an imported MP3, M4A or WAV file, or a recording in AAC format (`capture.m4a`). For this audio, the transcription encodes the audio again as Opus at 24 kb/s and puts it into the document. Then Cassini deletes its copy of the original file. Thus, the quality of the audio in Cassini can be lower than the quality of the original file. Your original file outside Cassini does not change.

## Network

Cassini connects to the internet only to download models. It sends no audio, transcripts, voiceprints, names or settings.

Cassini downloads no model before you agree. The "Transcribe on this phone" screen shows the size of each model. The first download starts only when you push "Download and transcribe" or "Download". If you push "Not now — keep the audio", Cassini downloads nothing. After you agree, Cassini downloads a missing model, or a model that it did not download fully, when you start a transcription.

Cassini downloads each model from one host:

- Speech recognition (NVIDIA Parakeet TDT 0.6B v3, INT8): `huggingface.co`
- Speech detection (Silero VAD): `github.com`
- Telling speakers apart (NVIDIA Nemotron-3-Diarization, INT8): `dist.gocassini.com`
- Recognising saved voices (3D-Speaker CAM++): `github.com`

A host can send the download to a different host (a redirect). Cassini follows a redirect only to an HTTPS host in its allow-list (a list of permitted hosts). This allow-list is the list in `ModelCatalog.DOWNLOAD_HOSTS`:

- `huggingface.co` and its subdomains
- The subdomains of `hf.co` (the file storage of Hugging Face)
- `github.com`
- The subdomains of `githubusercontent.com` (the file storage of GitHub releases)
- `dist.gocassini.com`

If a redirect goes to a different host, Cassini stops the download of that model.

A host receives the IP address of your phone and the name of the file. Android also sends a standard user agent, which gives the Android version and the phone model. The privacy policy of each host applies to these data. Cassini sends no cookies and no account data.

Before Cassini uses a downloaded file, it makes sure that the size and the SHA-256 hash of the file are correct. The security policy of Cassini gives the details: https://github.com/codemyriad/cassini-android/blob/main/SECURITY.md

## Permissions

- Microphone: Cassini uses the microphone only while you record. Cassini shows a notification during the recording.
- Notifications: Cassini shows the progress of recordings, downloads and transcriptions. On a secure lock screen, these notifications do not show the title of the note.
- Internet: Cassini uses the internet only for the model downloads.

## Backup and device transfer

Android does not back up the Cassini data and does not copy it to a new phone. Thus, if you lose the phone or you remove the app, you lose your notes. To keep a note, save a copy of it.

## Copies that you save

Cassini writes a file outside its storage only when you tell it to. On the "Save a copy" menu of a note, you select the format. Then Android asks you for the location. The formats are:

- Cassini document (`.opus`): the audio, all transcripts, the speaker names, the title, the recording time, and the names of the models that made each transcript.
- Audio file: the audio of a note that has no transcript.
- Transcript as text (`.txt`) and subtitles (`.srt`, `.vtt`): the words, their times and the speaker names.

These files contain no voiceprints. Cassini cannot delete a copy that you saved or sent to somebody.

## Deletion

### Notes

When you move a note to the trash, Cassini keeps it for 30 days. You can restore it during this time. Notes in Trash are deleted the first time Cassini runs on or after the 30th day.

On the Trash screen, "Delete now" deletes one note immediately. "Empty" deletes all notes in the trash immediately.

When Cassini deletes a note, it deletes all of these:

- The audio and the Cassini document of the note
- The unfinished transcription and its audio clips
- The voiceprints of the note, in `voices/notes/` and in `voices/pending/`
- The database records of the note

A saved voice can come from a note that you delete. The deletion of the note does not remove the saved voice. To remove a saved voice, use "Forget".

### Saved voices

Open Settings, then "Saved voices":

- "Forget" removes one saved voice. Cassini also removes the matches with this voice from all notes.
- "Forget everyone" deletes all voiceprints of Cassini 0.2: the saved voices and the voiceprints of all notes. It does not delete the files of 0.1.x (see "Voiceprints").

After you forget a voice, the speaker names in your notes stay. To change a name in a note, give the speaker a different name.

### Everything

If you uninstall Cassini, Android deletes all Cassini data, including the models. "Clear storage" in the Android app settings has the same result.

## Questions and problems

If Cassini does not do what this file says, tell us. The security policy of Cassini tells you how to send a private report: https://github.com/codemyriad/cassini-android/blob/main/SECURITY.md
