package com.devmind.document.chunker;

import java.util.List;

public interface TextChunker {

    List<TextChunk> chunk(String text);
}
