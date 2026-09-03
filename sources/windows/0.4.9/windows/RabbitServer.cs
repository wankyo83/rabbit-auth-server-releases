using System;
using System.IO;
using System.Text;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using System.Web.Script.Serialization;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Collections.Generic;
using System.Collections;
using System.Reflection;
using System.Net;
using System.IO.Compression;
using System.Globalization;

[assembly: AssemblyVersion("0.4.9.0")]
[assembly: AssemblyFileVersion("0.4.9.0")]

// Real headed Chromium on a separate Win32 desktop, not the user's desktop.
// No fingerprint overrides, desktop switching, injected input or global process kills.
sealed class PrivateWorker : IDisposable {
    IntPtr desktop, job, process;
    public int Pid;
    public string DesktopName;
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct SI {
        public int cb; public string reserved, desktop, title;
        public int x,y,xSize,ySize,xChars,yChars,fill,flags;
        public short show,reserved2; public IntPtr reservedPtr,input,output,error;
    }
    [StructLayout(LayoutKind.Sequential)] struct PI { public IntPtr process,thread; public int pid,tid; }
    [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
        public long perProcess,perJob; public uint flags; public UIntPtr min,max;
        public uint active; public UIntPtr affinity; public uint priority,scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] struct IOCounters { public ulong readOps,writeOps,otherOps,readBytes,writeBytes,otherBytes; }
    [StructLayout(LayoutKind.Sequential)] struct Limits {
        public BasicLimits basic; public IOCounters io; public UIntPtr processMemory,jobMemory,peakProcess,peakJob;
    }
    [DllImport("user32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateDesktop(string name,IntPtr device,IntPtr mode,int flags,uint access,IntPtr security);
    [DllImport("user32.dll")] static extern bool CloseDesktop(IntPtr h);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcess(string app,StringBuilder command,IntPtr pa,IntPtr ta,bool inherit,uint flags,IntPtr env,string cwd,ref SI si,out PI pi);
    [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr security,string name);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr h,int type,ref Limits limits,int length);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr h,uint code);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr h,uint time);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr h,out uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    public bool Exited { get {return process==IntPtr.Zero || WaitForSingleObject(process,0)==0;} }
    public uint ExitCode { get {uint code;GetExitCodeProcess(process,out code);return code;} }
    static string Q(string s) { if(s.Contains("\""))throw new ArgumentException("Invalid path");return "\""+s+"\""; }
    public PrivateWorker(string root,string script,string config,string key) {
        DesktopName="RabbitAuth_"+Guid.NewGuid().ToString("N");
        desktop=CreateDesktop(DesktopName,IntPtr.Zero,IntPtr.Zero,0,0x01ff,IntPtr.Zero);
        if(desktop==IntPtr.Zero)throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(),"별도 브라우저 화면을 만들 수 없습니다.");
        try {
            job=CreateJobObject(IntPtr.Zero,null);
            if(job==IntPtr.Zero)throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            Limits limits=new Limits();limits.basic.flags=0x2000; // KILL_ON_JOB_CLOSE, only owned descendants
            if(!SetInformationJobObject(job,9,ref limits,Marshal.SizeOf(limits)))throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            SI si=new SI();si.cb=Marshal.SizeOf(si);si.desktop="winsta0\\"+DesktopName;
            PI pi;string exe=Path.Combine(root,"runtime","node.exe");
            string oldKey=Environment.GetEnvironmentVariable("SERVER_KEY"),oldBrowsers=Environment.GetEnvironmentVariable("PLAYWRIGHT_BROWSERS_PATH"),oldNodeOptions=Environment.GetEnvironmentVariable("NODE_OPTIONS");
            bool started;
            try {
                Environment.SetEnvironmentVariable("SERVER_KEY",key);
                Environment.SetEnvironmentVariable("PLAYWRIGHT_BROWSERS_PATH",Path.Combine(root,"runtime","browsers"));
                Environment.SetEnvironmentVariable("NODE_OPTIONS",null);
                started=CreateProcess(exe,new StringBuilder(Q(exe)+" "+Q(script)+" "+Q(config)),IntPtr.Zero,IntPtr.Zero,false,0x08000004,IntPtr.Zero,root,ref si,out pi);
            } finally {
                Environment.SetEnvironmentVariable("SERVER_KEY",oldKey);Environment.SetEnvironmentVariable("PLAYWRIGHT_BROWSERS_PATH",oldBrowsers);Environment.SetEnvironmentVariable("NODE_OPTIONS",oldNodeOptions);
            }
            if(!started)throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error(),"서버 실행 실패. runtime 폴더를 확인하세요.");
            process=pi.process;Pid=pi.pid;
            if(!AssignProcessToJobObject(job,process)){int e=Marshal.GetLastWin32Error();TerminateProcess(process,1);CloseHandle(pi.thread);throw new System.ComponentModel.Win32Exception(e);}
            uint resumed=ResumeThread(pi.thread);CloseHandle(pi.thread);
            if(resumed==0xffffffff){TerminateProcess(process,1);throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());}
        } catch {Dispose();throw;}
    }
    public void Dispose(){if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;}if(process!=IntPtr.Zero){CloseHandle(process);process=IntPtr.Zero;}if(desktop!=IntPtr.Zero){CloseDesktop(desktop);desktop=IntPtr.Zero;}}
}

sealed class Settings {
    public int port=9870;
    public string protectedKey="";
    public bool notifyUpdates=true;
    public string lastUpdateCheckUtc="";
    public string lastNotifiedVersion="";
}

static class UiPalette {
    public static readonly Color Canvas=Color.FromArgb(245,248,252);
    public static readonly Color Surface=Color.White;
    public static readonly Color Border=Color.FromArgb(220,226,235);
    public static readonly Color Text=Color.FromArgb(20,31,48);
    public static readonly Color Muted=Color.FromArgb(91,107,128);
    public static readonly Color Primary=Color.FromArgb(21,101,232);
    public static readonly Color PrimaryHover=Color.FromArgb(12,88,215);
    public static readonly Color Success=Color.FromArgb(18,150,104);
    public static readonly Color Warning=Color.FromArgb(244,168,20);
    public static readonly Color Danger=Color.FromArgb(211,63,73);
    public static readonly Color Disabled=Color.FromArgb(236,240,245);
}

static class UiShapes {
    public static GraphicsPath Rounded(Rectangle bounds,int radius) {
        GraphicsPath path=new GraphicsPath();int d=Math.Min(radius*2,Math.Min(bounds.Width,bounds.Height));
        if(d<=1){path.AddRectangle(bounds);return path;}
        path.AddArc(bounds.Left,bounds.Top,d,d,180,90);path.AddArc(bounds.Right-d,bounds.Top,d,d,270,90);
        path.AddArc(bounds.Right-d,bounds.Bottom-d,d,d,0,90);path.AddArc(bounds.Left,bounds.Bottom-d,d,d,90,90);path.CloseFigure();return path;
    }
}

sealed class CardPanel : Panel {
    public Color SurfaceColor=UiPalette.Surface;public Color BorderColor=UiPalette.Border;public int Radius=16;
    public CardPanel(){DoubleBuffered=true;BackColor=UiPalette.Canvas;Padding=new Padding(22);}
    protected override void OnPaint(PaintEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;Rectangle r=new Rectangle(1,1,Width-3,Height-3);
        using(GraphicsPath p=UiShapes.Rounded(r,Radius))using(SolidBrush b=new SolidBrush(SurfaceColor))using(Pen pen=new Pen(BorderColor)){e.Graphics.FillPath(b,p);e.Graphics.DrawPath(pen,p);}base.OnPaint(e);}
}

sealed class ModernButton : Button {
    public Color NormalColor=Color.White,HoverColor=Color.FromArgb(247,249,252),PressedColor=Color.FromArgb(235,240,247),TextColor=UiPalette.Text,OutlineColor=UiPalette.Border;
    public int Radius=9;bool hover,pressed;
    public ModernButton(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer,true);FlatStyle=FlatStyle.Flat;FlatAppearance.BorderSize=0;Cursor=Cursors.Hand;Font=new Font("맑은 고딕",10,FontStyle.Bold);Height=44;TabStop=true;DoubleBuffered=true;TextAlign=ContentAlignment.MiddleCenter;Padding=new Padding(0);}
    public void SetPrimary(){NormalColor=UiPalette.Primary;HoverColor=UiPalette.PrimaryHover;PressedColor=Color.FromArgb(8,75,187);TextColor=Color.White;OutlineColor=UiPalette.Primary;}
    protected override void OnMouseEnter(EventArgs e){hover=true;Invalidate();base.OnMouseEnter(e);}protected override void OnMouseLeave(EventArgs e){hover=false;pressed=false;Invalidate();base.OnMouseLeave(e);}
    protected override void OnMouseDown(MouseEventArgs e){pressed=true;Invalidate();base.OnMouseDown(e);}protected override void OnMouseUp(MouseEventArgs e){pressed=false;Invalidate();base.OnMouseUp(e);}
    protected override void OnEnabledChanged(EventArgs e){Invalidate();base.OnEnabledChanged(e);}
    protected override void OnPaint(PaintEventArgs e){e.Graphics.Clear(Parent==null?UiPalette.Canvas:Parent.BackColor);e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;Rectangle r=new Rectangle(0,0,Width-1,Height-1);
        Color fill=!Enabled?UiPalette.Disabled:(pressed?PressedColor:(hover?HoverColor:NormalColor));Color fg=!Enabled?Color.FromArgb(145,156,171):TextColor;
        using(GraphicsPath p=UiShapes.Rounded(r,Radius))using(SolidBrush b=new SolidBrush(fill))using(Pen pen=new Pen(!Enabled?UiPalette.Border:OutlineColor)){e.Graphics.FillPath(b,p);e.Graphics.DrawPath(pen,p);}
        string glyph="",caption=Text;int split=Text.IndexOf("  ",StringComparison.Ordinal);
        if(split>0){glyph=Text.Substring(0,split);caption=Text.Substring(split+2);}
        TextRenderer.DrawText(e.Graphics,caption,Font,r,fg,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis|TextFormatFlags.NoPadding);
        if(glyph.Length>0)TextRenderer.DrawText(e.Graphics,glyph,Font,new Rectangle(18,0,30,Height),fg,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter|TextFormatFlags.NoPadding);
        if(Focused&&ShowFocusCues){Rectangle f=Rectangle.Inflate(r,-4,-4);ControlPaint.DrawFocusRectangle(e.Graphics,f,fg,fill);}}
}

sealed class StatusBadge : Control {
    string caption="중지됨";Color dot=Color.FromArgb(105,117,132),fill=Color.FromArgb(245,247,250),border=UiPalette.Border;
    public StatusBadge(){DoubleBuffered=true;Size=new Size(112,36);Font=new Font("맑은 고딕",9,FontStyle.Bold);}
    public void SetState(string text,Color dotColor,Color fillColor,Color borderColor){caption=text;dot=dotColor;fill=fillColor;border=borderColor;Invalidate();}
    protected override void OnPaint(PaintEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;Rectangle r=new Rectangle(0,0,Width-1,Height-1);
        using(GraphicsPath p=UiShapes.Rounded(r,18))using(SolidBrush b=new SolidBrush(fill))using(Pen pen=new Pen(border)){e.Graphics.FillPath(b,p);e.Graphics.DrawPath(pen,p);}
        using(SolidBrush b=new SolidBrush(dot))e.Graphics.FillEllipse(b,14,13,10,10);TextRenderer.DrawText(e.Graphics,caption,Font,new Rectangle(31,0,Width-38,Height),UiPalette.Text,TextFormatFlags.Left|TextFormatFlags.VerticalCenter|TextFormatFlags.EndEllipsis);}
}

sealed class RabbitMark : Control {
    readonly Bitmap logo;
    public RabbitMark(){DoubleBuffered=true;Size=new Size(104,102);logo=LoadExtensionIcon();}
    static Bitmap LoadExtensionIcon(){
        Stream stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("RabbitAuthServer.NewtokiWebtoonIcon");
        if(stream==null)throw new InvalidOperationException("뉴토끼 웹툰 아이콘을 불러오지 못했습니다.");
        using(stream)using(Bitmap source=new Bitmap(stream))return new Bitmap(source);
    }
    protected override void OnPaint(PaintEventArgs e){
        e.Graphics.Clear(Parent==null?UiPalette.Canvas:Parent.BackColor);
        e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
        e.Graphics.InterpolationMode=InterpolationMode.HighQualityBicubic;
        e.Graphics.PixelOffsetMode=PixelOffsetMode.HighQuality;

        // 고해상도 배율에서도 로고와 배치 칸을 함께 확대한다. 고정 102px로
        // 그리면 배치 칸만 커져 제목 앞에 불필요한 공백이 생긴다.
        Rectangle target=ClientRectangle;
        if(target.Width<=0||target.Height<=0)return;
        int radius=Math.Max(18,Math.Min(target.Width,target.Height)/5);
        using(GraphicsPath clip=UiShapes.Rounded(target,radius)){
            GraphicsState state=e.Graphics.Save();
            e.Graphics.SetClip(clip);

            // 원본 확장앱 아이콘의 반투명 검은 모서리를 잘라낸다.
            int crop=Math.Max(3,Math.Min(target.Width,target.Height)/32);
            Rectangle expanded=new Rectangle(target.X-crop,target.Y-crop,target.Width+crop*2,target.Height+crop*2);
            e.Graphics.DrawImage(logo,expanded);
            e.Graphics.Restore(state);
        }
    }
    protected override void Dispose(bool disposing){if(disposing&&logo!=null)logo.Dispose();base.Dispose(disposing);}
}

sealed class StatusDot : Control {
    public Color DotColor=UiPalette.Warning;public StatusDot(){DoubleBuffered=true;Size=new Size(52,52);}
    protected override void OnPaint(PaintEventArgs e){e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;using(SolidBrush b=new SolidBrush(Color.FromArgb(34,DotColor)))e.Graphics.FillEllipse(b,0,0,51,51);using(SolidBrush b=new SolidBrush(DotColor))e.Graphics.FillEllipse(b,20,20,12,12);}
}

sealed class UiLabel : Label {
    public UiLabel(){UseCompatibleTextRendering=false;}
    protected override void OnPaint(PaintEventArgs e){
        TextFormatFlags flags=TextFormatFlags.PreserveGraphicsClipping|TextFormatFlags.SingleLine;
        switch(TextAlign){
            case ContentAlignment.TopCenter:flags|=TextFormatFlags.Top|TextFormatFlags.HorizontalCenter;break;
            case ContentAlignment.TopRight:flags|=TextFormatFlags.Top|TextFormatFlags.Right;break;
            case ContentAlignment.MiddleLeft:flags|=TextFormatFlags.VerticalCenter|TextFormatFlags.Left;break;
            case ContentAlignment.MiddleCenter:flags|=TextFormatFlags.VerticalCenter|TextFormatFlags.HorizontalCenter;break;
            case ContentAlignment.MiddleRight:flags|=TextFormatFlags.VerticalCenter|TextFormatFlags.Right;break;
            case ContentAlignment.BottomLeft:flags|=TextFormatFlags.Bottom|TextFormatFlags.Left;break;
            case ContentAlignment.BottomCenter:flags|=TextFormatFlags.Bottom|TextFormatFlags.HorizontalCenter;break;
            case ContentAlignment.BottomRight:flags|=TextFormatFlags.Bottom|TextFormatFlags.Right;break;
            default:flags|=TextFormatFlags.Top|TextFormatFlags.Left;break;
        }
        if(AutoEllipsis)flags|=TextFormatFlags.EndEllipsis;
        Font renderedFont=Font,emphasis=null;
        try{
            if(Font.Style==FontStyle.Regular){emphasis=new Font(Font,FontStyle.Bold);renderedFont=emphasis;}
            TextRenderer.DrawText(e.Graphics,Text,renderedFont,ClientRectangle,ForeColor,flags);
        }finally{if(emphasis!=null)emphasis.Dispose();}
    }
}

static class BuildInfo {
    public const string UpdateManifestUrl="https://raw.githubusercontent.com/wankyo83/rabbit-auth-server-releases/main/updates/windows.json";
    public static string Version {
        get {
            Version value=Assembly.GetExecutingAssembly().GetName().Version;
            return value.Major+"."+value.Minor+"."+value.Build;
        }
    }
}

enum FirewallAccessState { Allowed, Partial, Blocked, Unavailable }

sealed class FirewallAccessInfo {
    public FirewallAccessState State;
    public string Text;
    public FirewallAccessInfo(FirewallAccessState state,string text){State=state;Text=text;}
}

static class FirewallSupport {
    const string RuleName="Rabbit 인증 서버";
    const int Inbound=1,AllowAction=1,BlockAction=0,AllProfiles=Int32.MaxValue;

    static object Property(object target,string name){return target.GetType().InvokeMember(name,BindingFlags.GetProperty,null,target,null);}
    static int Number(object value){return Convert.ToInt32(value);}
    static void Release(object value){if(value!=null&&Marshal.IsComObject(value))try{Marshal.FinalReleaseComObject(value);}catch{}}

    public static FirewallAccessInfo Inspect(string applicationPath){
        if(!File.Exists(applicationPath))return new FirewallAccessInfo(FirewallAccessState.Unavailable,"방화벽 확인 불가");
        object policy=null,rules=null;
        try{
            Type type=Type.GetTypeFromProgID("HNetCfg.FwPolicy2");
            if(type==null)return new FirewallAccessInfo(FirewallAccessState.Unavailable,"방화벽 확인 불가");
            policy=Activator.CreateInstance(type);int active=Number(Property(policy,"CurrentProfileTypes"));
            if(active<=0)active=1|2|4;
            rules=Property(policy,"Rules");int allowedProfiles=0,blockedProfiles=0;
            foreach(object rule in (IEnumerable)rules){
                try{
                    if(!Convert.ToBoolean(Property(rule,"Enabled"))||Number(Property(rule,"Direction"))!=Inbound)continue;
                    string app=Convert.ToString(Property(rule,"ApplicationName"));
                    if(String.IsNullOrWhiteSpace(app)||!String.Equals(Path.GetFullPath(app),Path.GetFullPath(applicationPath),StringComparison.OrdinalIgnoreCase))continue;
                    int profiles=Number(Property(rule,"Profiles"));int matched=profiles==AllProfiles?active:(profiles&active);
                    if(Number(Property(rule,"Action"))==AllowAction)allowedProfiles|=matched;else if(Number(Property(rule,"Action"))==BlockAction)blockedProfiles|=matched;
                }catch{}finally{Release(rule);}
            }
            int effective=allowedProfiles&~blockedProfiles;
            if((effective&active)==active)return new FirewallAccessInfo(FirewallAccessState.Allowed,"방화벽 허용됨");
            if((effective&active)!=0)return new FirewallAccessInfo(FirewallAccessState.Partial,"일부 네트워크 차단");
            return new FirewallAccessInfo(FirewallAccessState.Blocked,"방화벽 차단됨");
        }catch{return new FirewallAccessInfo(FirewallAccessState.Unavailable,"방화벽 확인 불가");}
        finally{Release(rules);Release(policy);}
    }

    static string PsLiteral(string value){return "'"+value.Replace("'","''")+"'";}
    public static string BuildAllowScript(string applicationPath){
        string name=PsLiteral(RuleName),program=PsLiteral(Path.GetFullPath(applicationPath));
        return "$ErrorActionPreference='Stop';$n="+name+";$p="+program+";"+
            "Get-NetFirewallRule -DisplayName $n -ErrorAction SilentlyContinue|Remove-NetFirewallRule;"+
            "New-NetFirewallRule -DisplayName $n -Description 'Rabbit 인증 서버 외부 기기 연결 허용' -Direction Inbound -Action Allow -Program $p -Profile Any -Enabled True|Out-Null;";
    }
    public static async Task Allow(string applicationPath){
        if(!File.Exists(applicationPath))throw new FileNotFoundException("인증 서버 실행 파일을 찾을 수 없습니다.",applicationPath);
        string encoded=Convert.ToBase64String(Encoding.Unicode.GetBytes(BuildAllowScript(applicationPath)));
        var start=new ProcessStartInfo("powershell.exe","-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand "+encoded){UseShellExecute=true,Verb="runas",WindowStyle=ProcessWindowStyle.Hidden};
        using(Process process=Process.Start(start)){await Task.Run(()=>process.WaitForExit());if(process.ExitCode!=0)throw new InvalidOperationException("Windows 방화벽 허용을 완료하지 못했습니다.");}
    }
}

sealed class UpdateManifest {
    public string version="";
    public string downloadUrl="";
    public string fileName="";
    public string sha256="";
}

static class UpdateSupport {
    static readonly JavaScriptSerializer Json=new JavaScriptSerializer();
    public static void EnableModernTls(){ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;}
    public static bool IsNewer(string candidate,string current){
        Version next,now;return Version.TryParse(candidate,out next)&&Version.TryParse(current,out now)&&next>now;
    }
    public static async Task<UpdateManifest> Fetch(){
        EnableModernTls();
        using(var web=new WebClient()){web.Headers[HttpRequestHeader.UserAgent]="RabbitAuthServer-Windows/"+BuildInfo.Version;web.Headers[HttpRequestHeader.CacheControl]="no-cache";
            string body=await web.DownloadStringTaskAsync(new Uri(BuildInfo.UpdateManifestUrl+"?ts="+DateTime.UtcNow.Ticks));
            UpdateManifest info=Json.Deserialize<UpdateManifest>(body);
            Uri uri;if(info==null||!Uri.TryCreate(info.downloadUrl,UriKind.Absolute,out uri)||uri.Scheme!="https"||String.IsNullOrWhiteSpace(info.sha256)||info.sha256.Length!=64)throw new InvalidDataException("업데이트 정보가 올바르지 않습니다.");
            return info;
        }
    }
    public static async Task<string> DownloadAndExtract(UpdateManifest info){
        EnableModernTls();
        string updateRoot=Path.Combine(Path.GetTempPath(),"RabbitAuthServer-Update-"+Guid.NewGuid().ToString("N"));
        string zip=Path.Combine(updateRoot,"package.zip"),extracted=Path.Combine(updateRoot,"package");Directory.CreateDirectory(extracted);
        using(var web=new WebClient()){web.Headers[HttpRequestHeader.UserAgent]="RabbitAuthServer-Windows/"+BuildInfo.Version;await web.DownloadFileTaskAsync(new Uri(info.downloadUrl),zip);}
        string actual;using(var sha=SHA256.Create())using(var input=File.OpenRead(zip))actual=BitConverter.ToString(sha.ComputeHash(input)).Replace("-","").ToLowerInvariant();
        if(!String.Equals(actual,info.sha256,StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("다운로드 파일의 검증값이 일치하지 않습니다.");
        using(var archive=ZipFile.OpenRead(zip))foreach(var entry in archive.Entries){
            string destination=Path.GetFullPath(Path.Combine(extracted,entry.FullName));
            if(!destination.StartsWith(Path.GetFullPath(extracted)+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))throw new InvalidDataException("안전하지 않은 압축 파일입니다.");
        }
        ZipFile.ExtractToDirectory(zip,extracted);
        foreach(string required in new[]{"RabbitAuthServer.exe","runtime","server","checksums.json"})if(!File.Exists(Path.Combine(extracted,required))&&!Directory.Exists(Path.Combine(extracted,required)))throw new InvalidDataException("업데이트 파일 구성이 올바르지 않습니다: "+required);
        return extracted;
    }
    public static void LaunchInstaller(string source,string target,bool restart=true){
        string helper=Path.Combine(Path.GetTempPath(),"RabbitAuthUpdater-"+Guid.NewGuid().ToString("N")+".exe");File.Copy(Assembly.GetExecutingAssembly().Location,helper,true);
        var arguments=(restart?"--apply-update ":"--apply-update-test ")+QuoteArgument(source)+" "+QuoteArgument(target)+" "+Process.GetCurrentProcess().Id;
        var start=new ProcessStartInfo(helper,arguments){UseShellExecute=true,WorkingDirectory=Path.GetTempPath()};
        Process.Start(start);
    }
    // Windows 명령행에서 따옴표 바로 앞의 역슬래시는 따옴표를 이스케이프한다.
    // 설치 폴더는 보통 역슬래시로 끝나므로 끝의 역슬래시를 두 배로 만들어야 한다.
    static string QuoteArgument(string value){
        if(value==null)return "\"\"";
        var result=new StringBuilder("\"");int slashes=0;
        foreach(char ch in value){
            if(ch=='\\'){slashes++;continue;}
            if(ch=='\"'){result.Append('\\',slashes*2+1);result.Append('\"');slashes=0;continue;}
            if(slashes>0){result.Append('\\',slashes);slashes=0;}result.Append(ch);
        }
        if(slashes>0)result.Append('\\',slashes*2);
        result.Append('\"');return result.ToString();
    }
    public static int Apply(string source,string target,int oldPid,bool restart=true){
        try{
            source=Path.GetFullPath(source);target=Path.GetFullPath(target);
            if(!File.Exists(Path.Combine(source,"RabbitAuthServer.exe"))||!Directory.Exists(Path.Combine(source,"server"))||!Directory.Exists(Path.Combine(source,"runtime")))throw new InvalidDataException("업데이트 원본이 올바르지 않습니다.");
            try{Process.GetProcessById(oldPid).WaitForExit(60000);}catch(ArgumentException){}
            CopyTree(source,target);if(restart)Process.Start(new ProcessStartInfo(Path.Combine(target,"RabbitAuthServer.exe")){UseShellExecute=true,WorkingDirectory=target});
            try{Directory.Delete(source,true);}catch{}
            return 0;
        }catch(Exception e){MessageBox.Show("자동 업데이트를 완료하지 못했습니다.\n\n"+e.Message+"\n\n기존 프로그램은 그대로 사용할 수 있습니다.","Rabbit 인증 서버 업데이트");return 1;}
    }
    static void CopyTree(string source,string target){
        Directory.CreateDirectory(target);
        foreach(string dir in Directory.GetDirectories(source,"*",SearchOption.AllDirectories))Directory.CreateDirectory(Path.Combine(target,dir.Substring(source.Length).TrimStart(Path.DirectorySeparatorChar)));
        foreach(string file in Directory.GetFiles(source,"*",SearchOption.AllDirectories)){string relative=file.Substring(source.Length).TrimStart(Path.DirectorySeparatorChar);string destination=Path.Combine(target,relative);Directory.CreateDirectory(Path.GetDirectoryName(destination));File.Copy(file,destination,true);}
    }
}

static class UpdateNotificationPolicy {
    public static bool ShouldNotify(string candidate,string current,string lastNotified,bool automatic){
        if(!UpdateSupport.IsNewer(candidate,current))return false;
        return !automatic||!String.Equals(candidate,lastNotified,StringComparison.OrdinalIgnoreCase);
    }
}

sealed class FooterShield : Control {
    public FooterShield(){DoubleBuffered=true;Size=new Size(30,30);}
    protected override void OnPaint(PaintEventArgs e){
        e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
        Rectangle r=new Rectangle(3,2,23,25);
        using(GraphicsPath p=new GraphicsPath()){
            p.AddLines(new[]{new Point(r.Left+r.Width/2,r.Top),new Point(r.Right,r.Top+5),new Point(r.Right-2,r.Bottom-7),new Point(r.Left+r.Width/2,r.Bottom),new Point(r.Left+2,r.Bottom-7),new Point(r.Left,r.Top+5)});
            p.CloseFigure();using(SolidBrush b=new SolidBrush(UiPalette.Primary))e.Graphics.FillPath(b,p);
        }
        using(Pen pen=new Pen(Color.White,2)){e.Graphics.DrawLine(pen,9,14,20,14);e.Graphics.DrawLine(pen,14,9,14,20);}
    }
}

sealed class AddressRow : Panel {
    public AddressRow(string address,Action<string> copy){
        DoubleBuffered=true;BackColor=Color.White;Dock=DockStyle.Fill;Margin=new Padding(0);Padding=new Padding(14,3,8,3);
        var grid=new TableLayoutPanel{Dock=DockStyle.Fill,BackColor=Color.White,ColumnCount=2,RowCount=1,Margin=new Padding(0)};
        grid.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));grid.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,122));
        var text=new UiLabel{Text=address,ForeColor=UiPalette.Text,Font=new Font("Segoe UI",11),AutoSize=false,Dock=DockStyle.Fill,TextAlign=ContentAlignment.MiddleLeft,AutoEllipsis=true};
        text.DoubleClick+=(s,e)=>copy(address);grid.Controls.Add(text,0,0);
        var button=new ModernButton{Text="▣  주소 복사",Dock=DockStyle.Fill,Margin=new Padding(8,2,0,2)};button.Click+=(s,e)=>copy(address);grid.Controls.Add(button,1,0);
        Controls.Add(grid);
    }
}

sealed partial class ServerForm : Form {
    readonly string root=AppDomain.CurrentDomain.BaseDirectory;
    readonly string dataDir=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"RabbitAuthServer");
    readonly JavaScriptSerializer json=new JavaScriptSerializer();
    readonly List<string> displayedAddresses=new List<string>();
    PrivateWorker worker;string control;bool closing,stopping,updateChecking; DateTime startedAt,nextFirewallCheck,nextAutomaticUpdateCheck;
    Settings settingsConfig=new Settings();
    string FirewallApplicationPath { get {return Path.Combine(root,"runtime","node.exe");} }
    static void SecureDirectory(string dir) {
        Directory.CreateDirectory(dir);DirectorySecurity security=new DirectorySecurity();security.SetAccessRuleProtection(true,false);
        security.AddAccessRule(new FileSystemAccessRule(WindowsIdentity.GetCurrent().User,FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));
        security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(WellKnownSidType.LocalSystemSid,null),FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow));
        Directory.SetAccessControl(dir,security);
    }
    public ServerForm() {
        InitializeComponent();SetUiState("stopped","서버가 중지되어 있습니다","접속 키를 확인한 뒤 서버를 시작하세요.");
        tray.Icon=SystemIcons.Application;tray.Text="Rabbit 인증 서버";tray.Visible=true;tray.DoubleClick+=(s,e)=>Restore();
        var menu=new ContextMenuStrip();menu.Items.Add("설정 창 열기",null,(s,e)=>Restore());menu.Items.Add("관리 화면",null,(s,e)=>OpenManagement());menu.Items.Add("종료",null,(s,e)=>Close());tray.ContextMenuStrip=menu;
        timer.Interval=1000;timer.Tick+=(s,e)=>Poll();timer.Start();
        FormClosing+=ServerForm_FormClosing;
        SecureDirectory(dataDir);SecureDirectory(Path.Combine(dataDir,"control"));
        try{settingsConfig=json.Deserialize<Settings>(File.ReadAllText(Path.Combine(dataDir,"settings.json")))??new Settings();}catch{settingsConfig=new Settings();}
        if(settingsConfig.port>=1024&&settingsConfig.port<=65535)port.Value=settingsConfig.port;
        try{if(!String.IsNullOrEmpty(settingsConfig.protectedKey))key.Text=Encoding.UTF8.GetString(ProtectedData.Unprotect(Convert.FromBase64String(settingsConfig.protectedKey),null,DataProtectionScope.CurrentUser));}catch{}
        notifyUpdates.Checked=settingsConfig.notifyUpdates;
        DateTime last;if(DateTime.TryParse(settingsConfig.lastUpdateCheckUtc,CultureInfo.InvariantCulture,DateTimeStyles.RoundtripKind,out last)&&last.ToUniversalTime()>DateTime.UtcNow.AddHours(-24))nextAutomaticUpdateCheck=last.ToUniversalTime().AddHours(24);
        else nextAutomaticUpdateCheck=DateTime.UtcNow.AddSeconds(3);
    }
    void ShowKey_Click(object sender,EventArgs e){key.UseSystemPasswordChar=!key.UseSystemPasswordChar;show.Text=key.UseSystemPasswordChar?"보기":"숨김";}
    void Start_Click(object sender,EventArgs e){StartServer();}
    async void Stop_Click(object sender,EventArgs e){await StopServer();}
    void Open_Click(object sender,EventArgs e){OpenManagement();}
    void HideToTray_Click(object sender,EventArgs e){Hide();}
    async void Update_Click(object sender,EventArgs e){await CheckForUpdate(true);}
    void NotifyUpdates_CheckedChanged(object sender,EventArgs e){settingsConfig.notifyUpdates=notifyUpdates.Checked;SaveSettings();if(notifyUpdates.Checked&&nextAutomaticUpdateCheck<DateTime.UtcNow)nextAutomaticUpdateCheck=DateTime.UtcNow.AddSeconds(3);}
    async void FirewallAllow_Click(object sender,EventArgs e){
        firewallAllow.Enabled=false;firewallStatus.Text="관리자 승인 대기 중…";firewallStatus.ForeColor=UiPalette.Primary;
        try{
            await FirewallSupport.Allow(FirewallApplicationPath);UpdateFirewallStatus(true);
            MessageBox.Show("Rabbit 인증 서버가 Windows 방화벽의 허용 앱으로 등록되었습니다.","Windows 방화벽",MessageBoxButtons.OK,MessageBoxIcon.Information);
        }catch(System.ComponentModel.Win32Exception ex){
            if(ex.NativeErrorCode==1223)MessageBox.Show("관리자 권한 승인이 취소되어 방화벽 설정을 변경하지 않았습니다.","Windows 방화벽",MessageBoxButtons.OK,MessageBoxIcon.Information);
            else MessageBox.Show("방화벽 설정을 변경하지 못했습니다.\n\n"+ex.Message,"Windows 방화벽",MessageBoxButtons.OK,MessageBoxIcon.Warning);
        }catch(Exception ex){MessageBox.Show("방화벽 설정을 변경하지 못했습니다.\n\n"+ex.Message,"Windows 방화벽",MessageBoxButtons.OK,MessageBoxIcon.Warning);}
        finally{UpdateFirewallStatus(true);}
    }
    async void ServerForm_FormClosing(object sender,FormClosingEventArgs e){if(closing)return;e.Cancel=true;if(worker!=null&&!worker.Exited&&MessageBox.Show("서버를 중지하고 종료할까요? 계속 사용하려면 취소 후 '트레이로 숨기기'를 누르세요.","Rabbit 인증 서버",MessageBoxButtons.OKCancel)!=DialogResult.OK)return;await StopServer();closing=true;tray.Visible=false;Close();}
    void SetUiState(string state,string title,string message){statusTitle.Text=title;status.Text=message;
        if(state=="ready"){statusDot.DotColor=UiPalette.Success;badge.SetState("실행 중",UiPalette.Success,Color.FromArgb(235,249,243),Color.FromArgb(175,227,206));}
        else if(state=="starting"||state=="stopping"){statusDot.DotColor=UiPalette.Primary;badge.SetState(state=="starting"?"시작 중":"종료 중",UiPalette.Primary,Color.FromArgb(236,244,255),Color.FromArgb(183,207,246));}
        else if(state=="failed"){statusDot.DotColor=UiPalette.Danger;badge.SetState("확인 필요",UiPalette.Danger,Color.FromArgb(255,241,243),Color.FromArgb(241,189,196));}
        else{statusDot.DotColor=UiPalette.Warning;badge.SetState("중지됨",UiPalette.Danger,Color.FromArgb(255,241,243),Color.FromArgb(241,189,196));}statusDot.Invalidate();}
    void CopyAddress(string address){if(String.IsNullOrWhiteSpace(address))return;try{Clipboard.SetText(address);status.Text="선택한 서버 주소를 클립보드에 복사했습니다.";}catch(Exception e){MessageBox.Show("주소를 복사하지 못했습니다. "+e.Message,"Rabbit 인증 서버");}}
    void RenderAddresses(List<string> addresses){
        bool changed=displayedAddresses.Count!=addresses.Count;for(int i=0;!changed&&i<addresses.Count;i++)changed=displayedAddresses[i]!=addresses[i];if(!changed)return;
        displayedAddresses.Clear();displayedAddresses.AddRange(addresses);addressRows.SuspendLayout();addressRows.Controls.Clear();addressRows.RowStyles.Clear();addressRows.RowCount=Math.Max(1,addresses.Count);
        if(addresses.Count==0){addressRows.RowStyles.Add(new RowStyle(SizeType.Percent,100));}
        else for(int i=0;i<addresses.Count;i++){addressRows.RowStyles.Add(new RowStyle(SizeType.Absolute,46));addressRows.Controls.Add(new AddressRow(addresses[i],CopyAddress),0,i);}
        addressRows.ResumeLayout(true);
    }
    void Restore(){Show();WindowState=FormWindowState.Normal;Activate();}
    void OpenManagement(){if(worker!=null&&!worker.Exited)Process.Start(new ProcessStartInfo("http://127.0.0.1:"+(int)port.Value){UseShellExecute=true});}
    void SaveSettings(){
        try{File.WriteAllText(Path.Combine(dataDir,"settings.json"),json.Serialize(settingsConfig),new UTF8Encoding(false));}catch{}
    }
    async Task CheckForUpdate(bool manual){
        if(updateChecking)return;updateChecking=true;
        if(manual){update.Enabled=false;update.Text="확인 중…";}
        try{
            UpdateManifest info=await UpdateSupport.Fetch();
            settingsConfig.lastUpdateCheckUtc=DateTime.UtcNow.ToString("o",CultureInfo.InvariantCulture);nextAutomaticUpdateCheck=DateTime.UtcNow.AddHours(24);SaveSettings();
            if(!UpdateSupport.IsNewer(info.version,BuildInfo.Version)){if(manual)MessageBox.Show("현재 최신 버전(v"+BuildInfo.Version+")을 사용 중입니다.","업데이트 확인");return;}
            if(!UpdateNotificationPolicy.ShouldNotify(info.version,BuildInfo.Version,settingsConfig.lastNotifiedVersion,!manual))return;
            if(!manual){settingsConfig.lastNotifiedVersion=info.version;SaveSettings();tray.ShowBalloonTip(5000,"Rabbit 인증 서버 업데이트","새 버전 v"+info.version+"이 있습니다.",ToolTipIcon.Info);}
            string message="새 버전이 있습니다.\n\n현재 버전: v"+BuildInfo.Version+"\n최신 버전: v"+info.version+"\n\n지금 다운로드하고 설치할까요?\n서버가 실행 중이면 안전하게 중지한 뒤 프로그램을 다시 시작합니다.";
            if(MessageBox.Show(message,"Rabbit 인증 서버 업데이트",MessageBoxButtons.YesNo,MessageBoxIcon.Information)!=DialogResult.Yes)return;
            update.Text="다운로드 중…";string source=await UpdateSupport.DownloadAndExtract(info);
            if(worker!=null&&!worker.Exited)await StopServer();
            UpdateSupport.LaunchInstaller(source,root);closing=true;tray.Visible=false;Close();
        }catch(Exception e){if(manual)MessageBox.Show("업데이트를 확인하거나 설치 파일을 준비하지 못했습니다.\n\n"+e.Message,"Rabbit 인증 서버 업데이트",MessageBoxButtons.OK,MessageBoxIcon.Warning);else nextAutomaticUpdateCheck=DateTime.UtcNow.AddHours(1);}
        finally{updateChecking=false;if(!closing){update.Enabled=true;update.Text="업데이트 확인";}}
    }
    void StartServer(){
        if(worker!=null&&!worker.Exited)return;
        if(!System.Text.RegularExpressions.Regex.IsMatch(key.Text,"^[\\x21-\\x7e]{4,128}$")||key.Text.StartsWith("replace-with-")){MessageBox.Show("접속 키는 공백 없는 숫자·영문·기호 4~128자로 입력하세요.");return;}
        try {
            worker=DisposeWorker(worker);
            settingsConfig.port=(int)port.Value;settingsConfig.protectedKey=Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(key.Text),null,DataProtectionScope.CurrentUser));SaveSettings();
            control=Path.Combine(dataDir,"control",Guid.NewGuid().ToString("N")+".json");
            File.WriteAllText(control,json.Serialize(new{port=(int)port.Value,concurrency=2,dataDir=dataDir,parentPid=Process.GetCurrentProcess().Id}),new UTF8Encoding(false));
            worker=new PrivateWorker(root,Path.Combine(root,"server","windows","server.mjs"),control,key.Text);startedAt=DateTime.UtcNow;
            start.Enabled=false;stop.Enabled=true;port.Enabled=false;key.Enabled=false;SetUiState("starting","서버를 시작하고 있습니다","인증 브라우저를 준비하는 중입니다. 잠시만 기다려 주세요.");
        }catch(Exception e){SetUiState("failed","서버를 시작하지 못했습니다",e.Message);worker=DisposeWorker(worker);start.Enabled=true;stop.Enabled=false;port.Enabled=true;key.Enabled=true;}
    }
    static PrivateWorker DisposeWorker(PrivateWorker w){if(w!=null)w.Dispose();return null;}
    void UpdateFirewallStatus(bool force=false){
        if(!force&&DateTime.UtcNow<nextFirewallCheck)return;nextFirewallCheck=DateTime.UtcNow.AddSeconds(3);
        FirewallAccessInfo info=FirewallSupport.Inspect(FirewallApplicationPath);firewallStatus.Text=info.Text;
        if(info.State==FirewallAccessState.Allowed){firewallStatus.ForeColor=UiPalette.Success;firewallAllow.Text="허용됨";firewallAllow.Enabled=false;}
        else if(info.State==FirewallAccessState.Partial){firewallStatus.ForeColor=UiPalette.Warning;firewallAllow.Text="방화벽 허용";firewallAllow.Enabled=true;}
        else if(info.State==FirewallAccessState.Blocked){firewallStatus.ForeColor=UiPalette.Danger;firewallAllow.Text="방화벽 허용";firewallAllow.Enabled=true;}
        else{firewallStatus.ForeColor=UiPalette.Muted;firewallAllow.Text="방화벽 허용";firewallAllow.Enabled=false;}
    }
    async void Poll(){UpdateFirewallStatus();if(notifyUpdates.Checked&&!updateChecking&&DateTime.UtcNow>=nextAutomaticUpdateCheck){nextAutomaticUpdateCheck=DateTime.UtcNow.AddHours(24);await CheckForUpdate(false);}if(worker==null||stopping)return;
        try{var s=json.Deserialize<Dictionary<string,object>>(File.ReadAllText(control+".status"));string state=s["state"].ToString();
            if(state=="ready"){SetUiState("ready","서버가 정상 실행 중입니다","인증 브라우저는 필요할 때만 별도 화면에서 실행됩니다.");open.Enabled=true;List<string> next=new List<string>();
                foreach(object ip in (System.Collections.IEnumerable)s["addresses"])next.Add("http://"+ip+":"+(int)port.Value);if(next.Count==0)next.Add("http://127.0.0.1:"+(int)port.Value);
                RenderAddresses(next);}
            if(state=="failed")SetUiState("failed","서버에서 오류를 보고했습니다",s["error"].ToString());
        }catch{}
        if(worker.Exited){bool hasStatus=File.Exists(control+".status");if(!hasStatus || open.Enabled)SetUiState("failed","서버가 예기치 않게 종료되었습니다","runtime 폴더와 보안 프로그램의 차단 내역을 확인하세요.");worker=DisposeWorker(worker);start.Enabled=true;stop.Enabled=false;open.Enabled=false;port.Enabled=true;key.Enabled=true;RenderAddresses(new List<string>());}
        else if(!open.Enabled&&DateTime.UtcNow-startedAt>TimeSpan.FromSeconds(45))SetUiState("failed","서버 시작이 지연되고 있습니다","중지 후 runtime 폴더와 보안 프로그램을 확인하세요.");
    }
    async Task StopServer(){if(stopping||worker==null)return;stopping=true;stop.Enabled=false;SetUiState("stopping","서버를 종료하고 있습니다","브라우저와 진행 중인 작업을 안전하게 정리하는 중입니다.");
        try{File.WriteAllText(control+".stop","stop");for(int i=0;i<100&&!worker.Exited;i++)await Task.Delay(150);}
        finally{worker=DisposeWorker(worker);stopping=false;SetUiState("stopped","서버가 중지되었습니다","인증 쿠키와 연결 설정은 다음 실행을 위해 보관됩니다.");start.Enabled=true;stop.Enabled=false;open.Enabled=false;port.Enabled=true;key.Enabled=true;RenderAddresses(new List<string>());}}
    protected override void Dispose(bool disposing){if(disposing){timer.Dispose();tray.Dispose();if(worker!=null)worker.Dispose();}base.Dispose(disposing);}
}

static class Program {
    [STAThread] static int Main(string[] args){
        UpdateSupport.EnableModernTls();
        if(args.Length==2&&args[0]=="--firewall-status-smoke"){
            try{
                string app=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"runtime","node.exe");FirewallAccessInfo info=FirewallSupport.Inspect(app);
                File.WriteAllText(Path.GetFullPath(args[1]),new JavaScriptSerializer().Serialize(new{state=info.State.ToString(),text=info.Text,program=app}),new UTF8Encoding(false));return info.State==FirewallAccessState.Unavailable?1:0;
            }catch(Exception e){File.WriteAllText(Path.GetFullPath(args[1]),new JavaScriptSerializer().Serialize(new{state="Unavailable",text=e.Message}),new UTF8Encoding(false));return 1;}
        }
        if(args.Length==2&&args[0]=="--firewall-script-smoke"){
            string app=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"runtime","node.exe");File.WriteAllText(Path.GetFullPath(args[1]),FirewallSupport.BuildAllowScript(app),new UTF8Encoding(false));return 0;
        }
        if(args.Length==2&&args[0]=="--update-check-smoke"){
            try{UpdateManifest info=UpdateSupport.Fetch().GetAwaiter().GetResult();File.WriteAllText(Path.GetFullPath(args[1]),new JavaScriptSerializer().Serialize(new{ok=true,version=info.version,url=info.downloadUrl}),new UTF8Encoding(false));return 0;}
            catch(Exception e){File.WriteAllText(Path.GetFullPath(args[1]),new JavaScriptSerializer().Serialize(new{ok=false,error=e.Message}),new UTF8Encoding(false));return 1;}
        }
        if(args.Length==2&&args[0]=="--update-notification-smoke"){
            string current=BuildInfo.Version,candidate="9.9.9";
            File.WriteAllText(Path.GetFullPath(args[1]),new JavaScriptSerializer().Serialize(new{
                firstAutomatic=UpdateNotificationPolicy.ShouldNotify(candidate,current,"",true),
                repeatedAutomatic=UpdateNotificationPolicy.ShouldNotify(candidate,current,candidate,true),
                manual=UpdateNotificationPolicy.ShouldNotify(candidate,current,candidate,false),
                older=UpdateNotificationPolicy.ShouldNotify("0.0.1",current,"",true)
            }),new UTF8Encoding(false));return 0;
        }
        if(args.Length==3&&args[0]=="--launch-update-test"){
            UpdateSupport.LaunchInstaller(Path.GetFullPath(args[1]),Path.GetFullPath(args[2])+Path.DirectorySeparatorChar,false);return 0;
        }
        if(args.Length==1&&args[0]=="--ui-preview"){
            Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);Application.Run(new ServerForm());return 0;
        }
        int oldPid;if(args.Length==4&&(args[0]=="--apply-update"||args[0]=="--apply-update-test")&&Int32.TryParse(args[3],out oldPid))return UpdateSupport.Apply(args[1],args[2],oldPid,args[0]!="--apply-update-test");
        if(args.Length==2 && (args[0]=="--smoke" || args[0]=="--server-smoke")){
            string root=AppDomain.CurrentDomain.BaseDirectory;
            using(var w=new PrivateWorker(root,Path.Combine(root,"server","windows",args[0]=="--smoke"?"smoke-worker.mjs":"server-smoke.mjs"),Path.GetFullPath(args[1]),"test-key-only")){
                for(int i=0;i<180&&!w.Exited;i++)Thread.Sleep(500);return w.Exited?(int)w.ExitCode:124;
            }
        }
        bool created;using(var mutex=new Mutex(true,"Local\\RabbitAuthServerWindows",out created)){
            if(!created){MessageBox.Show("이미 실행 중입니다. 작업 표시줄의 트레이에서 Rabbit 인증 서버를 여세요.");return 1;}
            Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
            try{Application.Run(new ServerForm());return 0;}catch(Exception e){MessageBox.Show(e.Message,"Rabbit 서버 실행 오류");return 1;}
        }
    }
}
