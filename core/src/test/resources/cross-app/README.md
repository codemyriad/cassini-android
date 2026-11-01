# Cross-app vectors

`two-versions-default-switched.opus` is a byte-for-byte copy of the shared vector in the coordination repository
(`ux-draft/cross-app-vectors`, decision D53.3), sha256
`d4bffa94eec9481f19668e53ee247f8e5a107402a0740094a0cc29b3252b9f6d`. It is conformance vector 016 of cassini-format
867f68c with the default moved to the second version, built by the format's own tools, without app code. CC0-1.0, like
the conformance suite (`third_party/cassini-format/LICENSE-CC0-1.0.txt`). `CassiniDocumentSetDefaultTest` checks that
Android opens it on `second-pass`, and that Android's own version switch writes the same manifest and tags.
