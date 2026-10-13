# Secure sync format migration

Account sync now writes `termora-gcm-v2:` envelopes with a fresh 12-byte nonce
for every encryption. The owner ID and object ID are authenticated alongside
the encrypted data. Existing account ciphertext remains readable, but older
clients cannot read new envelopes. Upgrade every device before syncing.

The Sync plugin (GitHub, Gitee, GitLab and WebDAV) now requires a separate
encryption password of at least 12 characters. Use the same password on every
device. It must differ from the provider token or WebDAV password, and is never
included in requests to the provider. Losing this password prevents recovery of
the new encrypted files. Changing provider credentials no longer changes the
encryption key for the new format.

Each sync file, including IDs, timestamps and deletion records, is encrypted in
a `termora-pbe-v2:` envelope using PBKDF2-HMAC-SHA512 (210,000 iterations), a fresh
16-byte salt, AES-256-GCM and a fresh 12-byte nonce. The filename is authenticated
to prevent substitution between data types. All selected files are authenticated
before the pull applies changes. Wrong passwords, invalid tags and unsupported
versions fail without falling back to the legacy format.

To migrate third-party sync data:

1. Back up the local data directory and the remote sync files. Upgrade the app
   and Sync plugin together on every device, and pause old clients.
2. Set an independent encryption password in Sync settings. Keep the credentials
   used to encrypt the old files until migration finishes: legacy keys were
   derived from the provider token or WebDAV credentials.
3. Select every data category that needs migration and explicitly enable
   **Import legacy data**. Do this only for a trusted backup: the legacy CBC
   format cannot authenticate its contents or deletion records.
4. Sync. The manual action imports the selected legacy data, then writes the
   selected files using the new format. The legacy-import checkbox is cleared
   after a successful write. Automatic sync never enables legacy import.
5. Configure the same encryption password on the other upgraded devices.
   Unselected remote legacy files remain in their old format until migrated.

To change the encryption password, retain the old password while reading the
old destination, then write the complete local data to a new Gist/Snippet or
WebDAV destination with the new password. Do not erase the only readable copy
before verifying the new destination. Rewriting current files does not remove
older revisions or undo previous exposure of credentials.

The backend implementation and old-client coordination are outside this source
checkout; validate the new account envelopes against the deployed service before
releasing an upgrade.
