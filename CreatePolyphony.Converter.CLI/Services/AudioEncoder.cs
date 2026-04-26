using OggVorbisEncoder;

namespace CreatePolyphony.Converter.CLI.Services;

public class AudioEncoder
{
    public void EncodeOgg(short[] left, short[] right, int sampleRate, string outputPath)
    {
        VorbisInfo? info = VorbisInfo.InitVariableBitRate(2, sampleRate, 0.5f);
        int serialNo = new Random().Next();
        var oggStream = new OggStream(serialNo);
        var processingState = ProcessingState.Create(info);

        using var fs = new FileStream(outputPath, FileMode.Create, FileAccess.Write);

        // 1. Generate and write mandatory Vorbis headers
        var comments = new Comments();

        oggStream.PacketIn(HeaderPacketBuilder.BuildInfoPacket(info));
        oggStream.PacketIn(HeaderPacketBuilder.BuildCommentsPacket(comments));
        oggStream.PacketIn(HeaderPacketBuilder.BuildBooksPacket(info));

        FlushPages(oggStream, fs, force: true);

        // 2. Encode audio data
        int bufferSize = 1024;
        for (int offset = 0; offset < left.Length; offset += bufferSize)
        {
            int remaining = Math.Min(bufferSize, left.Length - offset);
            float[][] writeBuffer = new float[2][];
            writeBuffer[0] = new float[remaining];
            writeBuffer[1] = new float[remaining];

            for (int i = 0; i < remaining; i++)
            {
                writeBuffer[0][i] = left[offset + i] / (float)short.MaxValue;
                writeBuffer[1][i] = right[offset + i] / (float)short.MaxValue;
            }

            processingState.WriteData(writeBuffer, remaining);
            WriteVorbisBlocks(processingState, oggStream, fs);
        }

        // 3. Finalize stream
        processingState.WriteEndOfStream();
        WriteVorbisBlocks(processingState, oggStream, fs);
        FlushPages(oggStream, fs, force: true);
    }

    private static void WriteVorbisBlocks(ProcessingState state, OggStream stream, FileStream fs)
    {
        while (state.PacketOut(out OggPacket packet))
        {
            stream.PacketIn(packet);
            FlushPages(stream, fs, force: false);
        }
    }

    private static void FlushPages(OggStream stream, FileStream fs, bool force)
    {
        while (stream.PageOut(out OggPage page, force))
        {
            fs.Write(page.Header, 0, page.Header.Length);
            fs.Write(page.Body, 0, page.Body.Length);
        }
    }
}
