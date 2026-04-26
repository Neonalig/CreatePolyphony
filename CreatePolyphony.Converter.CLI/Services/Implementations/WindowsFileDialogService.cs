using System.Runtime.InteropServices;

namespace CreatePolyphony.Converter.CLI.Services.Implementations;

public partial class WindowsFileDialogService : IFileDialogService
{
    [LibraryImport("comdlg32.dll", EntryPoint = "GetOpenFileNameW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static unsafe partial bool GetOpenFileName(ref OpenFileName ofn);

    [LibraryImport("comdlg32.dll", EntryPoint = "GetSaveFileNameW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static unsafe partial bool GetSaveFileName(ref OpenFileName ofn);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    [SuppressMessage("ReSharper", "IdentifierTypo")]
    private unsafe struct OpenFileName
    {
        public int lStructSize;
        public IntPtr hwndOwner;
        public IntPtr hInstance;
        public char* lpstrFilter;
        public char* lpstrCustomFilter;
        public int nMaxCustFilter;
        public int nFilterIndex;
        public char* lpstrFile;
        public int nMaxFile;
        public char* lpstrFileTitle;
        public int nMaxFileTitle;
        public char* lpstrInitialDir;
        public char* lpstrTitle;
        public int Flags;
        public short nFileOffset;
        public short nFileExtension;
        public char* lpstrDefExt;
        public IntPtr lCustData;
        public IntPtr lpfnHook;
        public char* lpTemplateName;
        public IntPtr pvReserved;
        public int dwReserved;
        public int FlagsEx;
    }

    public string? PromptForOpenFile(string filter)
    {
        return PromptForFile(filter, "Select SF2 or SFZ File", null, false);
    }

    public string? PromptForSaveFile(string filter, string defaultFileName)
    {
        return PromptForFile(filter, "Save Resource Pack Zip", defaultFileName, true);
    }

    private unsafe string? PromptForFile(string filter, string title, string? defaultFileName, bool saveDialog)
    {
        char[] fileBuffer = new char[4096];
        char[] fileTitleBuffer = new char[256];
        const string defaultExtension = "zip";

        if (!string.IsNullOrWhiteSpace(defaultFileName))
        {
            defaultFileName = Path.GetFileName(defaultFileName);
            defaultFileName.AsSpan(0, Math.Min(defaultFileName.Length, fileBuffer.Length - 1)).CopyTo(fileBuffer);
        }

        fixed (char* pFilter = filter)
        {
            fixed (char* pFile = fileBuffer)
            {
                fixed (char* pFileTitle = fileTitleBuffer)
                {
                    fixed (char* pTitle = title)
                    {
                        fixed (char* pDefaultExt = defaultExtension)
                        {
                            var ofn = new OpenFileName
                            {
                                lStructSize = sizeof(OpenFileName),
                                lpstrFilter = pFilter,
                                lpstrFile = pFile,
                                nMaxFile = fileBuffer.Length,
                                lpstrFileTitle = pFileTitle,
                                nMaxFileTitle = fileTitleBuffer.Length,
                                lpstrTitle = pTitle,
                                lpstrDefExt = saveDialog ? pDefaultExt : null,
                                Flags = saveDialog
                                    ? 0x00080000 | 0x00000002 | 0x00001000 // OFN_NOCHANGEDIR | OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST
                                    : 0x00080000 | 0x00001000 // OFN_NOCHANGEDIR | OFN_FILEMUSTEXIST
                            };

                            bool success = saveDialog ? GetSaveFileName(ref ofn) : GetOpenFileName(ref ofn);
                            return success ? new string(pFile) : null;
                        }
                    }
                }
            }
        }
    }
}