package com.asrtts;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioModel;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class AudioTranscriptionsExample {
    private static final Logger logger = LogManager.getLogger(AudioTranscriptionsExample.class);
    private AudioTranscriptionsExample() {}

    public static void main(String[] args) throws Exception {
        // Configures using one of:
        // - The `OPENAI_API_KEY` environment variable
        // - The `OPENAI_BASE_URL` and `AZURE_OPENAI_KEY` environment variables
        OpenAIClient client = OpenAIOkHttpClient.fromEnv();
        logger.debug("client "+client.toString());
        ClassLoader classloader = Thread.currentThread().getContextClassLoader();
      //  Path path = Paths.get(classloader.getResource("c:\\busy_simple.wav").toURI());
        
        Path path = Paths.get("C:\\busy_simple.wav");

        
        logger.debug("path "+path.toString());
        TranscriptionCreateParams createParams = TranscriptionCreateParams.builder()
                .file(path)
                .model(AudioModel.WHISPER_1)
                .build();

        Transcription transcription =
                client.audio().transcriptions().create(createParams).asTranscription();
        logger.debug(transcription.text());
    }
}
