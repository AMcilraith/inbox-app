# Embeds an .ico file as the icon of a Windows .exe (what Explorer, the taskbar and
# shortcuts show). Uses the Win32 resource-update API, so no resource compiler is needed.
#
#   .\scripts\set-exe-icon.ps1 -Exe target\release\inbox-hub.exe -Icon assets\icon.ico
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $Icon
)
$ErrorActionPreference = 'Stop'

if (-not ('IconRes' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class IconRes {
    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    static extern IntPtr BeginUpdateResource(string file, bool deleteExisting);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool UpdateResource(IntPtr h, IntPtr type, IntPtr name, ushort lang, byte[] data, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool EndUpdateResource(IntPtr h, bool discard);

    const int RT_ICON = 3, RT_GROUP_ICON = 14;

    public static void Set(string exe, string ico) {
        byte[] b = File.ReadAllBytes(ico);
        int count = BitConverter.ToUInt16(b, 4);
        IntPtr h = BeginUpdateResource(exe, false);
        if (h == IntPtr.Zero) throw new Win32Exception();
        var grp = new MemoryStream();
        var w = new BinaryWriter(grp);
        w.Write((ushort)0); w.Write((ushort)1); w.Write((ushort)count);
        for (int i = 0; i < count; i++) {
            int o = 6 + i * 16;
            uint size = BitConverter.ToUInt32(b, o + 8), offset = BitConverter.ToUInt32(b, o + 12);
            byte[] img = new byte[size];
            Array.Copy(b, offset, img, 0, size);
            if (!UpdateResource(h, (IntPtr)RT_ICON, (IntPtr)(i + 1), 0, img, size)) throw new Win32Exception();
            w.Write(b, o, 8);              // width, height, colours, reserved, planes, bit count
            w.Write(size);
            w.Write((ushort)(i + 1));      // resource id of the RT_ICON entry
        }
        byte[] g = grp.ToArray();
        if (!UpdateResource(h, (IntPtr)RT_GROUP_ICON, (IntPtr)1, 0, g, (uint)g.Length)) throw new Win32Exception();
        if (!EndUpdateResource(h, false)) throw new Win32Exception();
    }
}
'@
}
[IconRes]::Set((Resolve-Path $Exe).Path, (Resolve-Path $Icon).Path)
Write-Host "Icon set on $Exe"
