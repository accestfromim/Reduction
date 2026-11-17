// Reduction's desktop pet: a small black hole that lives on the desktop, always on top.
//
//   it breathes while idle, opens its wings while listening, spins them while thinking, pulses while speaking
//   the screen behind it is really bent round it, by the graphics card
//   hold the space bar and speak, let go to send   (anywhere on the computer; its own microphone)
//   click it to open or close the full panel       (cards, history, settings)
//   drag it anywhere; right-click for the menu
//
// It only shows what the service is doing and passes on what is done to it.
//
// How the bending works. Windows hands over the picture of the desktop straight from the graphics card
// (desktop duplication), only when it has changed, and without this window in it. The piece under the pet is
// laid under the pet as a picture, and a pixel shader — a few lines that run on the graphics card for every
// pixel — moves each pixel the way a heavy thing bends light.
//
//   ReductionPet.exe --url http://localhost:8780 --browser <exe> --profile <dir>
//   ReductionPet.exe --snapshot <png> --pose <idle|listening|thinking|speaking|asleep> [--at <seconds>]
//       draws the pet over a made-up background into a picture and leaves: how its looks are checked.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Effects;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;

// ── the bending, as the graphics card runs it ─────────────────────────────
class LensEffect : ShaderEffect {
  // Distances are in units of the square the pet sits in: its side is 1, its centre at 0.5.
  const string Source = @"
sampler2D behind : register(s0);
float swirl : register(c0);
float4 main(float2 uv : TEXCOORD) : COLOR {
  float2 d = uv - 0.5;
  float r = max(length(d), 0.0001);
  float hole = 0.23, outer = 0.495;
  float f = saturate((outer - r) / (outer - hole));      // 1 at the rim of the hole, 0 where the bending ends
  f = f * f * (3 - 2 * f);
  float from = r - 0.8 * hole * hole / r * f;            // how far out this pixel's light really comes from;
                                                         // below zero it came round the far side
  float s, c; sincos(swirl * f * f, s, c);               // and it is dragged round, most at the rim
  float2 dir = d / r;
  float4 light = tex2D(behind, 0.5 + float2(dir.x * c - dir.y * s, dir.x * s + dir.y * c) * from);
  float edge = saturate((outer - r) / (outer * 0.22));   // toward its outer edge the bent picture is all but the
  edge = edge * edge * (3 - 2 * edge);                   // real screen: there it thins out into it, so no edge shows
  float a = edge * step(hole * 0.55, r);
  return float4(light.rgb * a, a);
}";

  [DllImport("d3dcompiler_47.dll")]
  static extern int D3DCompile(byte[] source, UIntPtr size, string name, IntPtr defines, IntPtr include,
      [MarshalAs(UnmanagedType.LPStr)] string entry, [MarshalAs(UnmanagedType.LPStr)] string target, uint flags1, uint flags2, out IntPtr code, out IntPtr errors);

  public static readonly DependencyProperty InputProperty = RegisterPixelShaderSamplerProperty("Input", typeof(LensEffect), 0);
  public static readonly DependencyProperty SwirlProperty =
      DependencyProperty.Register("Swirl", typeof(double), typeof(LensEffect), new UIPropertyMetadata(0.8, PixelShaderConstantCallback(0)));
  public Brush Input { get { return (Brush)GetValue(InputProperty); } set { SetValue(InputProperty, value); } }
  public double Swirl { get { return (double)GetValue(SwirlProperty); } set { SetValue(SwirlProperty, value); } }

  /// The effect, or null when the shader cannot be built on this machine.
  public static LensEffect Create() {
    try {
      byte[] text = Encoding.ASCII.GetBytes(Source);
      IntPtr code, errors;
      // ps_2_0: the oldest kind there is, so it also runs where the graphics card is not used.
      if (D3DCompile(text, (UIntPtr)text.Length, "lens", IntPtr.Zero, IntPtr.Zero, "main", "ps_2_0", 0, 0, out code, out errors) < 0) return null;
      IntPtr at = Com.Call<Com.GetPointer>(code, 3)(code);
      byte[] bytes = new byte[(int)Com.Call<Com.GetPointer>(code, 4)(code)];
      Marshal.Copy(at, bytes, 0, bytes.Length);
      Marshal.Release(code);
      LensEffect effect = new LensEffect();
      PixelShader shader = new PixelShader();
      shader.SetStreamSource(new MemoryStream(bytes));
      effect.PixelShader = shader;
      effect.UpdateShaderValue(InputProperty);
      effect.UpdateShaderValue(SwirlProperty);
      return effect;
    } catch { return null; }
  }
}

// ── calling into the graphics system ──────────────────────────────────────
// Its objects are reached by the position of a method in the object's table; the positions are fixed by Windows.
static class Com {
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate IntPtr GetPointer(IntPtr self);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Query(IntPtr self, ref Guid id, out IntPtr found);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Get(IntPtr self, out IntPtr found);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int GetAt(IntPtr self, uint index, out IntPtr found);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int GetOutputDesc(IntPtr self, out OutputDesc desc);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Duplicate(IntPtr self, IntPtr device, out IntPtr duplication);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Acquire(IntPtr self, uint timeoutMs, out FrameInfo info, out IntPtr resource);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Simple(IntPtr self);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int CreateTexture(IntPtr self, ref TextureDesc desc, IntPtr initial, out IntPtr texture);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate void GetTextureDesc(IntPtr self, out TextureDesc desc);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate void CopyPart(IntPtr self, IntPtr to, uint toSub, uint x, uint y, uint z, IntPtr from, uint fromSub, ref Box box);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int GetRects(IntPtr self, uint size, IntPtr buffer, out uint used);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate int Map(IntPtr self, IntPtr resource, uint sub, int kind, uint flags, out Mapped mapped);
  [UnmanagedFunctionPointer(CallingConvention.StdCall)] public delegate void Unmap(IntPtr self, IntPtr resource, uint sub);

  [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)] public struct OutputDesc {
    [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string Name;
    public int Left, Top, Right, Bottom, Attached, Rotation; public IntPtr Monitor;
  }
  [StructLayout(LayoutKind.Sequential)] public struct FrameInfo {
    public long LastPresentTime, LastMouseUpdateTime; public uint AccumulatedFrames; public int RectsCoalesced, ProtectedContentMaskedOut;
    public int PointerX, PointerY, PointerVisible; public uint TotalMetadataBufferSize, PointerShapeBufferSize;
  }
  [StructLayout(LayoutKind.Sequential)] public struct TextureDesc {
    public uint Width, Height, MipLevels, ArraySize; public int Format; public uint SampleCount, SampleQuality; public int Usage; public uint BindFlags, CpuAccessFlags, MiscFlags;
  }
  [StructLayout(LayoutKind.Sequential)] public struct Box { public uint Left, Top, Front, Right, Bottom, Back; }
  [StructLayout(LayoutKind.Sequential)] public struct Mapped { public IntPtr Data; public uint RowPitch, DepthPitch; }

  public static T Call<T>(IntPtr self, int slot) where T : class {
    return (T)(object)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(Marshal.ReadIntPtr(self), slot * IntPtr.Size), typeof(T));
  }
  public static IntPtr As(IntPtr self, string id) {
    Guid guid = new Guid(id); IntPtr found;
    if (Call<Query>(self, 0)(self, ref guid, out found) < 0) throw new InvalidOperationException("no " + id);
    return found;
  }
}

// ── the picture of the desktop, from the graphics card ────────────────────
class Desktop {
  [DllImport("d3d11.dll")] static extern int D3D11CreateDevice(IntPtr adapter, int driver, IntPtr software, uint flags, IntPtr levels, uint levelCount, uint sdk, out IntPtr device, out int level, out IntPtr context);
  [DllImport("user32.dll")] static extern bool SetWindowDisplayAffinity(IntPtr window, uint affinity);
  /// Keeps a window out of every picture taken of the screen, this one's included. False where Windows cannot.
  public static bool KeepOut(IntPtr window) { return SetWindowDisplayAffinity(window, 0x11); }

  IntPtr device, context, output, duplication, small;     // small: a piece of the desktop the size of the pet, readable from here
  int left, top, width, height;
  public volatile int X, Y;                               // where on the screen the piece wanted is, set by the window
  public volatile float ReadMs; public volatile int Reads;
  readonly int side; readonly int[] piece; readonly object gate = new object(); volatile bool fresh, running;
  IntPtr rects = Marshal.AllocHGlobal(16 * 4096);

  public Desktop(int side) {
    this.side = side; piece = new int[side * side];
    int level;
    if (D3D11CreateDevice(IntPtr.Zero, 1, IntPtr.Zero, 0x20, IntPtr.Zero, 0, 7, out device, out level, out context) < 0) throw new InvalidOperationException("no graphics device");
    IntPtr dxgi = Com.As(device, "54ec77fa-1377-44e6-8c32-88fd5f44c84c"), adapter, first;
    Com.Call<Com.Get>(dxgi, 7)(dxgi, out adapter);
    if (Com.Call<Com.GetAt>(adapter, 7)(adapter, 0, out first) < 0) throw new InvalidOperationException("no screen");
    Com.OutputDesc desc; Com.Call<Com.GetOutputDesc>(first, 7)(first, out desc);
    left = desc.Left; top = desc.Top;
    output = Com.As(first, "00cddea8-939b-4b83-a340-a685226666cc");
    Open();
  }

  void Open() {
    if (Com.Call<Com.Duplicate>(output, 22)(output, device, out duplication) < 0) throw new InvalidOperationException("the desktop cannot be read");
  }

  public void Start() {
    running = true;
    Thread worker = new Thread(Run); worker.IsBackground = true; worker.Start();
  }
  public void Stop() { running = false; }

  /// Whether this frame changed anything inside the square at (x, y). Windows says which parts of the desktop changed.
  bool Touched(Com.FrameInfo info, int x, int y) {
    if (info.TotalMetadataBufferSize == 0) return false;
    if (info.TotalMetadataBufferSize > 16 * 4096) return true;                       // too much changed to list: assume so
    uint used;
    if (Com.Call<Com.GetRects>(duplication, 10)(duplication, 16 * 4096, rects, out used) < 0 || used > 0) return true;   // things were moved about
    if (Com.Call<Com.GetRects>(duplication, 9)(duplication, 16 * 4096, rects, out used) < 0) return true;
    for (int i = 0; i < used / 16; i++) {
      int l = Marshal.ReadInt32(rects, i * 16), t = Marshal.ReadInt32(rects, i * 16 + 4), r = Marshal.ReadInt32(rects, i * 16 + 8), b = Marshal.ReadInt32(rects, i * 16 + 12);
      if (l < x + side && r > x && t < y + side && b > y) return true;
    }
    return false;
  }

  void Run() {
    Stopwatch clock = Stopwatch.StartNew();
    int atX = int.MinValue, atY = int.MinValue; bool have = false;
    while (running) {
      try {
        Com.FrameInfo info; IntPtr resource;
        // Waits for the desktop to change, but no longer than a frame: the pet may have been moved meanwhile.
        int result = Com.Call<Com.Acquire>(duplication, 8)(duplication, 16, out info, out resource);
        if (result == unchecked((int)0x887A0026)) {         // the desktop was taken away (a mode change, the lock screen): ask again
          Marshal.Release(duplication); Thread.Sleep(500); Open(); have = false; continue;
        }
        if (result < 0) continue;                           // nothing changed in this while
        int x = X - left, y = Y - top;
        // Only the piece under the pet is fetched, and only when that piece changed or the pet has moved.
        if (info.LastPresentTime != 0 && (!have || x != atX || y != atY || Touched(info, x, y))) {
          long began = clock.ElapsedTicks;
          IntPtr texture = Com.As(resource, "6f15aaf2-d208-4e89-9ab4-489535d34f9c");
          if (small == IntPtr.Zero) {
            Com.TextureDesc desc; Com.Call<Com.GetTextureDesc>(texture, 10)(texture, out desc);
            width = (int)desc.Width; height = (int)desc.Height;
            desc.Width = desc.Height = (uint)side; desc.Usage = 3; desc.BindFlags = 0; desc.CpuAccessFlags = 0x20000; desc.MiscFlags = 0;
            Com.Call<Com.CreateTexture>(device, 5)(device, ref desc, IntPtr.Zero, out small);
          }
          // What lies off the screen is left out, and stays black.
          int fromX = Math.Max(0, x), fromY = Math.Max(0, y), toX = Math.Min(width, x + side), toY = Math.Min(height, y + side);
          if (toX > fromX && toY > fromY) {
            Com.Box box = new Com.Box { Left = (uint)fromX, Top = (uint)fromY, Front = 0, Right = (uint)toX, Bottom = (uint)toY, Back = 1 };
            Com.Call<Com.CopyPart>(context, 46)(context, small, 0, (uint)(fromX - x), (uint)(fromY - y), 0, texture, 0, ref box);
            Com.Mapped mapped;
            if (Com.Call<Com.Map>(context, 14)(context, small, 0, 1, 0, out mapped) >= 0) {
              lock (gate) {
                if (toX - fromX < side || toY - fromY < side) Array.Clear(piece, 0, piece.Length);
                for (int row = fromY - y; row < toY - y; row++) {
                  Marshal.Copy(new IntPtr(mapped.Data.ToInt64() + (long)row * mapped.RowPitch + (fromX - x) * 4), piece, row * side + (fromX - x), toX - fromX);
                }
                fresh = true;
              }
              Com.Call<Com.Unmap>(context, 15)(context, small, 0);
              atX = x; atY = y; have = true;
            }
          }
          Marshal.Release(texture);
          ReadMs = ReadMs * 0.9f + (float)((clock.ElapsedTicks - began) * 1000.0 / Stopwatch.Frequency) * 0.1f; Reads++;
        }
        Marshal.Release(resource);
        Com.Call<Com.Simple>(duplication, 14)(duplication);
      } catch { Thread.Sleep(200); }
    }
  }

  /// The newest piece, if there is one not yet shown.
  public bool Take(WriteableBitmap into) {
    if (!fresh) return false;
    lock (gate) { into.WritePixels(new Int32Rect(0, 0, side, side), piece, side * 4, 0); fresh = false; }
    return true;
  }
}

// ── the space bar, held ───────────────────────────────────────────────────
// Holding the space bar is how the pet is spoken to, whatever window is in front. A tap is still a space:
// the key is held back for a moment to see which it is, and a tap is passed on as it was. Typing on fast,
// with the next key down before the space bar is up, passes the space on at once, ahead of that key.
class SpaceKey {
  [StructLayout(LayoutKind.Sequential)] struct Stroke { public uint Key, Scan, Flags, Time; public IntPtr Extra; }
  delegate IntPtr Hook(int code, IntPtr message, IntPtr stroke);
  [DllImport("user32.dll")] static extern IntPtr SetWindowsHookEx(int kind, Hook hook, IntPtr module, uint thread);
  [DllImport("user32.dll")] static extern bool UnhookWindowsHookEx(IntPtr hook);
  [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr hook, int code, IntPtr message, IntPtr stroke);
  [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr GetModuleHandle(string name);
  [DllImport("user32.dll")] static extern short GetAsyncKeyState(int key);
  [DllImport("user32.dll")] static extern void keybd_event(byte key, byte scan, uint flags, UIntPtr extra);

  const int Space = 0x20;
  const uint Up = 2, Extended = 1, Injected = 0x10;
  public const int HoldMs = 350;

  public bool Enabled = true;
  public Action Held = delegate { }, LetGo = delegate { };

  readonly Hook keep;                    // kept, or the hook would be collected while Windows still calls it
  IntPtr hook;
  readonly DispatcherTimer wait = new DispatcherTimer();
  bool pending, talking, passing;

  public SpaceKey() {
    keep = Each;
    wait.Interval = TimeSpan.FromMilliseconds(HoldMs);
    wait.Tick += delegate {
      wait.Stop();
      if (!pending) return;
      pending = false; talking = true;
      try { Held(); } catch { }
    };
  }

  public void Start() { if (hook == IntPtr.Zero) hook = SetWindowsHookEx(13, keep, GetModuleHandle(null), 0); }
  public void Stop() {
    if (hook != IntPtr.Zero) UnhookWindowsHookEx(hook);
    hook = IntPtr.Zero;
    if (talking) { talking = false; try { LetGo(); } catch { } }
  }

  static bool IsDown(int key) { return (GetAsyncKeyState(key) & 0x8000) != 0; }

  IntPtr Each(int code, IntPtr message, IntPtr at) {
    if (code < 0) return CallNextHookEx(hook, code, message, at);
    Stroke stroke = (Stroke)Marshal.PtrToStructure(at, typeof(Stroke));
    if ((stroke.Flags & Injected) != 0) return CallNextHookEx(hook, code, message, at);       // what this very class passed on
    int kind = message.ToInt32();
    bool down = kind == 0x100 || kind == 0x104;

    if (stroke.Key != Space) {
      if (down && pending) {
        // Typing on: the space goes first, then this key, both as they were pressed.
        pending = false; wait.Stop(); passing = true;
        keybd_event(Space, 0x39, 0, UIntPtr.Zero);
        keybd_event((byte)stroke.Key, (byte)stroke.Scan, (stroke.Flags & 1) != 0 ? Extended : 0, UIntPtr.Zero);
        return (IntPtr)1;
      }
      return CallNextHookEx(hook, code, message, at);
    }

    if (down) {
      if (passing) return CallNextHookEx(hook, code, message, at);
      if (pending || talking) return (IntPtr)1;                                                // the key repeating while held
      // With Ctrl, Alt, Shift or the Windows key it is a shortcut of someone else's.
      if (!Enabled || IsDown(0x11) || IsDown(0x12) || IsDown(0x10) || IsDown(0x5B) || IsDown(0x5C)) {
        passing = true;
        return CallNextHookEx(hook, code, message, at);
      }
      pending = true; wait.Start();
      return (IntPtr)1;
    }

    if (passing) { passing = false; return CallNextHookEx(hook, code, message, at); }
    if (pending) {
      // A tap: the space it was, passed on whole.
      pending = false; wait.Stop();
      keybd_event(Space, 0x39, 0, UIntPtr.Zero);
      keybd_event(Space, 0x39, Up, UIntPtr.Zero);
      return (IntPtr)1;
    }
    if (talking) {
      talking = false;
      try { LetGo(); } catch { }
      return (IntPtr)1;
    }
    return CallNextHookEx(hook, code, message, at);
  }
}

// ── the pet ────────────────────────────────────────────────────────────────
class Pet {
  const string Look = @"
<Window xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation'
        Title='Reduction Pet' Width='320' Height='310' WindowStyle='None' AllowsTransparency='True'
        Background='Transparent' Topmost='True' ShowInTaskbar='False' ResizeMode='NoResize'>
  <Grid>
    <Border Name='Bubble' Visibility='Collapsed' VerticalAlignment='Top' HorizontalAlignment='Center' Margin='6,0,6,0'
            Background='#F0101010' BorderBrush='#40FFFFFF' BorderThickness='1' CornerRadius='14' Padding='12,8' MaxWidth='300'>
      <TextBlock Name='BubbleText' Foreground='#F2F2F2' FontSize='13' TextWrapping='Wrap' MaxHeight='96' TextTrimming='CharacterEllipsis'
                 FontFamily='Microsoft YaHei UI, Segoe UI'/>
    </Border>
    <Canvas Name='Pet' Width='200' Height='200' VerticalAlignment='Bottom' HorizontalAlignment='Center' Cursor='Hand' Background='#01000000'>
      <!-- The screen behind, bent. -->
      <Image Name='Behind' Width='200' Height='200' Stretch='Fill'/>
      <Ellipse Name='Glow' Canvas.Left='40' Canvas.Top='40' Width='120' Height='120' Opacity='0'>
        <Ellipse.Fill><RadialGradientBrush><GradientStop Color='#77FFFFFF' Offset='0.5'/><GradientStop Color='#00FFFFFF' Offset='1'/></RadialGradientBrush></Ellipse.Fill>
      </Ellipse>
      <!-- Black at the heart, thinning to nothing in a narrow band at the edge. -->
      <Ellipse Name='Body' Canvas.Left='44' Canvas.Top='44' Width='112' Height='112'>
        <Ellipse.Fill><RadialGradientBrush>
          <GradientStop Color='#FF040404' Offset='0'/><GradientStop Color='#FF040404' Offset='0.80'/><GradientStop Color='#00040404' Offset='0.90'/>
        </RadialGradientBrush></Ellipse.Fill>
      </Ellipse>
      <!-- The logo: a core, and two equal circles behind it of which only an arc shows. -->
      <Canvas Name='Face' Canvas.Left='52' Canvas.Top='52' Width='96' Height='96'>
        <Canvas.Clip><EllipseGeometry Center='48,48' RadiusX='44' RadiusY='44'/></Canvas.Clip>
        <Ellipse Name='SideL' Width='36' Height='36' Fill='#F2F2F2'/>
        <Ellipse Name='SideR' Width='36' Height='36' Fill='#F2F2F2'/>
        <Ellipse Name='Gap' Width='46.6' Height='46.6' Fill='#FF040404'/>
        <Ellipse Name='Core' Width='36' Height='36' Fill='#F2F2F2'/>
      </Canvas>
    </Canvas>
  </Grid>
</Window>";

  Window window; Canvas pet, face; Ellipse sideL, sideR, gap, core, body, glow; Image behind; Border bubble; TextBlock bubbleText;
  ScaleTransform gapScale = new ScaleTransform(), coreScale = new ScaleTransform();
  LensEffect lens; Desktop desktop; WriteableBitmap picture;
  volatile string state = "asleep"; string showing = "asleep";
  double t, blend, spinFrom = -10;

  string url = "http://localhost:8780", api, browser = "", profile = "", placeFile;
  long lineId = -1; DateTime bubbleUntil = DateTime.MinValue;
  bool down, moved, holding, wants; Point grab; string spaceFile;
  DispatcherTimer poll = new DispatcherTimer(); SpaceKey space = new SpaceKey();

  T Find<T>(string name) where T : class { return (T)window.FindName(name); }

  void Build() {
    window = (Window)XamlReader.Parse(Look);
    pet = Find<Canvas>("Pet"); face = Find<Canvas>("Face"); behind = Find<Image>("Behind");
    sideL = Find<Ellipse>("SideL"); sideR = Find<Ellipse>("SideR"); gap = Find<Ellipse>("Gap"); core = Find<Ellipse>("Core");
    body = Find<Ellipse>("Body"); glow = Find<Ellipse>("Glow"); bubble = Find<Border>("Bubble"); bubbleText = Find<TextBlock>("BubbleText");
    gap.RenderTransform = gapScale; core.RenderTransform = coreScale;
    lens = LensEffect.Create();
  }

  static void Put(Ellipse shape, double cx, double cy, double sx, double sy) {
    Canvas.SetLeft(shape, cx - shape.Width * sx / 2); Canvas.SetTop(shape, cy - shape.Height * sy / 2);
  }

  /// One frame's worth of movement. Everything follows from the state and the time.
  void Step(double dt) {
    t += dt;
    string now = state;
    if (showing != now) { showing = now; blend = 0; }
    blend = Math.Min(1, blend + dt * 2.4);
    double k = blend;
    // The figure is the logo, to scale: three circles of radius 82, the outer two centred 59 to either side.
    // Here the radius is 18, so the offset is 12.95.
    double spread = 12.95, size = 1, squash = 1, light = 0, bob = Math.Sin(t * 1.6) * 2, dim = 1, wind = 1;
    double lx = -1, ly = 0, rx = 1, ry = 0;
    switch (showing) {
      case "idle": {
        spread = 12.95 + Math.Sin(t * 1.6) * 0.6;
        double phase = t % 5.2;                                   // a blink now and then: the core flattens for a moment
        if (phase < 0.16) squash = 1 - Math.Sin(phase / 0.16 * Math.PI) * 0.85;
        if (t - spinFrom > 9) spinFrom = t;                       // and now and then the two wings swing once right round the core
        double turn = (t - spinFrom) / 1.3;
        if (turn < 1) {
          double a = (1 - Math.Cos(turn * Math.PI)) * Math.PI;
          lx = -Math.Cos(a); ly = -Math.Sin(a) * 0.6; rx = Math.Cos(a); ry = Math.Sin(a) * 0.6;
          spread = 12.95 + Math.Sin(turn * Math.PI) * 2;
        }
        break; }
      case "listening":
        spread = 12.95 + k * (4 + Math.Sin(t * 7)); size = 1 + k * 0.06; light = k * (0.55 + 0.3 * Math.Sin(t * 7)); bob = 0; wind = 0.8; break;
      case "thinking": {
        double a = t * 3.2;                                       // the two wings go round the core
        lx = -Math.Cos(a); ly = -Math.Sin(a) * 0.55; rx = Math.Cos(a); ry = Math.Sin(a) * 0.55; spread = 14; size = 0.94; wind = 2; break; }
      case "speaking": {
        double voice = Math.Abs(Math.Sin(t * 9)) * 0.6 + Math.Abs(Math.Sin(t * 5.3 + 1)) * 0.4;
        size = 1 + k * 0.10 * voice; spread = 12.95 + k * 2 * voice; light = k * 0.25 * voice; wind = 1.2 + 0.4 * voice; break; }
      default:
        dim = 0.45; squash = 0.18; bob = Math.Sin(t * 0.8) * 1.2; spread = 12; wind = 0.5; break;
    }
    Put(sideL, 48 + lx * spread, 48 + ly * spread, 1, 1); Put(sideR, 48 + rx * spread, 48 + ry * spread, 1, 1);
    gapScale.ScaleX = gapScale.ScaleY = size; Put(gap, 48, 48, size, size);
    coreScale.ScaleX = size; coreScale.ScaleY = size * squash; Put(core, 48, 48, size, size * squash);
    face.Opacity = dim; glow.Opacity = light;
    Canvas.SetTop(body, 44 + bob); Canvas.SetTop(face, 52 + bob); Canvas.SetTop(glow, 40 + bob);
    // How far round the background is dragged: it breathes, and winds up tighter the busier the pet is.
    if (lens != null) lens.Swirl = (0.75 + 0.3 * Math.Sin(t * 0.6)) * wind;
  }

  // ── drawn into a picture, for checking ──────────────────────────────────
  public void Snapshot(string file, string pose, double at) {
    Build();
    int side = 600; int[] grid = new int[side * side];
    for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) {
      bool line = x % 28 < 3 || y % 28 < 3;
      grid[y * side + x] = line ? unchecked((int)0xFFE8D8A0) : ((x / 84 + y / 84) % 2 == 0 ? unchecked((int)0xFF3A4A5C) : unchecked((int)0xFF4C3F66));
    }
    picture = new WriteableBitmap(side, side, 96, 96, PixelFormats.Bgr32, null);
    picture.WritePixels(new Int32Rect(0, 0, side, side), grid, side * 4, 0);
    behind.Source = picture; behind.Effect = lens;
    if (lens == null) Console.Error.WriteLine("the shader could not be built: the background is shown unbent");
    state = pose;
    for (int i = 0; i < (int)(at * 60); i++) Step(1 / 60.0);
    pet.Background = new ImageBrush(picture);                    // the plain background, to see the bent one against
    Size size = new Size(200, 200);
    pet.Measure(size); pet.Arrange(new Rect(size)); pet.UpdateLayout();
    RenderTargetBitmap shot = new RenderTargetBitmap(600, 600, 288, 288, PixelFormats.Pbgra32);
    shot.Render(pet);
    PngBitmapEncoder encoder = new PngBitmapEncoder();
    encoder.Frames.Add(BitmapFrame.Create(shot));
    using (FileStream stream = File.Create(file)) encoder.Save(stream);
  }

  // ── on the desktop ──────────────────────────────────────────────────────
  public void Run(string[] args) {
    for (int i = 0; i + 1 < args.Length; i += 2) {
      if (args[i] == "--url") url = args[i + 1]; else if (args[i] == "--browser") browser = args[i + 1]; else if (args[i] == "--profile") profile = args[i + 1];
    }
    // Asked by number: under the name "localhost" Windows first tries an address the service does not listen on, and waits.
    api = url.Replace("localhost", "127.0.0.1");
    string home = System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Reduction");
    Directory.CreateDirectory(home);
    placeFile = System.IO.Path.Combine(home, "pet-place.txt");
    Build();

    // Where it sat last time; otherwise the bottom right corner, above the taskbar.
    Rect area = SystemParameters.WorkArea;
    window.Left = area.Right - 330; window.Top = area.Bottom - 320;
    try {
      string[] place = File.ReadAllText(placeFile).Split(',');
      double x = double.Parse(place[0], System.Globalization.CultureInfo.InvariantCulture), y = double.Parse(place[1], System.Globalization.CultureInfo.InvariantCulture);
      if (x >= area.Left - 200 && x <= area.Right - 100 && y >= area.Top - 100 && y <= area.Bottom - 100) { window.Left = x; window.Top = y; }
    } catch { }

    window.ContentRendered += delegate { StartLens(); };
    double last = 0, since = 0; int frames = 0; Stopwatch clock = Stopwatch.StartNew(); string note = System.IO.Path.Combine(home, "pet-status.txt");
    CompositionTarget.Rendering += delegate {
      double now = clock.Elapsed.TotalSeconds, dt = Math.Min(0.1, now - last); last = now;
      Step(dt);
      if (desktop != null && PresentationSource.FromVisual(pet) != null) {
        Point corner = pet.PointToScreen(new Point(0, 0));
        desktop.X = (int)Math.Round(corner.X); desktop.Y = (int)Math.Round(corner.Y);
        desktop.Take(picture);
      }
      frames++;
      if (now - since >= 5) {                                    // how it is really doing, noted for whoever wants to know
        try {
          File.WriteAllText(note, string.Format("{0:0} frames a second; bending: {1}; the screen was read {2:0} times a second, {3:0.00} ms each",
              frames / (now - since), desktop == null ? "off" : (lens == null ? "read but not bent" : "on the graphics card"),
              desktop == null ? 0 : desktop.Reads / (now - since), desktop == null ? 0 : desktop.ReadMs));
        } catch { }
        frames = 0; since = now; if (desktop != null) desktop.Reads = 0;
      }
    };

    poll.Interval = TimeSpan.FromMilliseconds(350);
    poll.Tick += delegate {
      if (bubble.Visibility == Visibility.Visible && DateTime.Now > bubbleUntil && state != "listening") bubble.Visibility = Visibility.Collapsed;
      if (!holding) Ask("/pet", "GET", Heard);
    };
    poll.Start();

    // Speaking: the space bar held, anywhere. The pet itself is only clicked and dragged.
    space.Held = delegate {
      wants = true;
      Ask("/pet/talk?action=start", "POST", delegate(Dictionary<string, object> answer) {
        if (answer != null && (string)answer["state"] == "listening") {
          if (!wants) { Ask("/pet/talk?action=cancel", "POST", null); return; }  // let go before the microphone was open
          holding = true; state = "listening"; Say("在听…松开空格发送", 600);
        } else if (answer != null && !(bool)answer["ready"]) Say("先点我一下，在面板的设置里填 API Key", 0);
        else Say("用不了麦克风", 0);
      });
    };
    space.LetGo = delegate {
      wants = false;
      if (holding) { holding = false; Say("识别中…", 8); Ask("/pet/talk?action=stop", "POST", null); }
    };
    spaceFile = System.IO.Path.Combine(home, "pet-space.txt");
    try { space.Enabled = File.ReadAllText(spaceFile).Trim() != "off"; } catch { }
    space.Start();

    pet.MouseLeftButtonDown += delegate(object sender, MouseButtonEventArgs e) {
      down = true; moved = false; grab = e.GetPosition(window);
      pet.CaptureMouse();
    };
    pet.MouseMove += delegate(object sender, MouseEventArgs e) {
      if (!down || moved) return;
      Point at = e.GetPosition(window);
      if (Math.Abs(at.X - grab.X) + Math.Abs(at.Y - grab.Y) < 5) return;
      moved = true;
      // From here Windows itself moves the window with the mouse, until the button is let go.
      pet.ReleaseMouseCapture();
      try { window.DragMove(); } catch { }
      down = false;
      try { File.WriteAllText(placeFile, string.Format(System.Globalization.CultureInfo.InvariantCulture, "{0},{1}", window.Left, window.Top)); } catch { }
    };
    pet.MouseLeftButtonUp += delegate {
      pet.ReleaseMouseCapture();
      if (!down) return;
      down = false;
      if (!moved) TogglePanel();
    };

    ContextMenu menu = new ContextMenu();
    Add(menu, "打开或关闭面板", TogglePanel);
    Add(menu, "让它安静", delegate { Ask("/bridge/stopSpeaking", "POST", null); });
    MenuItem bySpace = new MenuItem(); bySpace.Header = "按住空格说话"; bySpace.IsCheckable = true; bySpace.IsChecked = space.Enabled;
    bySpace.Click += delegate {
      space.Enabled = bySpace.IsChecked;
      try { File.WriteAllText(spaceFile, space.Enabled ? "on" : "off"); } catch { }
    };
    menu.Items.Add(bySpace);
    Add(menu, "收起桌宠", delegate { window.Close(); });
    pet.ContextMenu = menu;

    window.Closed += delegate { space.Stop(); if (desktop != null) desktop.Stop(); };
    new Application().Run(window);
  }

  static void Add(ContextMenu menu, string text, Action act) {
    MenuItem item = new MenuItem(); item.Header = text; item.Click += delegate { act(); }; menu.Items.Add(item);
  }

  /// The screen behind is bent only when the pet can be kept out of the picture of it — otherwise it would bend itself.
  void StartLens() {
    if (desktop != null || lens == null) return;
    try {
      if (!Desktop.KeepOut(new WindowInteropHelper(window).Handle)) return;
      int side = (int)Math.Round(200 * PresentationSource.FromVisual(window).CompositionTarget.TransformToDevice.M11);
      picture = new WriteableBitmap(side, side, 96, 96, PixelFormats.Bgr32, null);
      Desktop made = new Desktop(side);
      behind.Source = picture; behind.Effect = lens;
      made.Start(); desktop = made;
    } catch { desktop = null; behind.Source = null; }
  }

  void Heard(Dictionary<string, object> now) {
    if (holding) return;
    if (now == null) { state = "asleep"; return; }
    state = (string)now["state"];
    long id = Convert.ToInt64(now["lineId"]);
    if (lineId < 0) lineId = id;                                   // nothing old is repeated when the pet starts
    else if (id != lineId) { lineId = id; Say((string)now["line"], 0); }
  }

  void Say(string text, double seconds) {
    if (string.IsNullOrEmpty(text)) return;
    if (text.Length > 90) text = text.Substring(0, 88) + "…";
    bubbleText.Text = text; bubble.Visibility = Visibility.Visible;
    if (seconds <= 0) seconds = Math.Min(12, Math.Max(3.5, text.Length * 0.16));
    bubbleUntil = DateTime.Now.AddSeconds(seconds);
  }

  /// Asks the service off the window's thread, and hands the answer (null when there is none) back on it.
  void Ask(string path, string method, Action<Dictionary<string, object>> then) {
    ThreadPool.QueueUserWorkItem(delegate {
      Dictionary<string, object> answer = null;
      try {
        HttpWebRequest request = (HttpWebRequest)WebRequest.Create(api + path);
        request.Proxy = null; request.Timeout = 1500; request.Method = method;
        if (method == "POST") { request.Headers.Add("X-Reduction", "1"); request.ContentLength = 0; }
        using (WebResponse response = request.GetResponse())
        using (StreamReader reader = new StreamReader(response.GetResponseStream(), Encoding.UTF8)) {
          answer = new System.Web.Script.Serialization.JavaScriptSerializer().Deserialize<Dictionary<string, object>>(reader.ReadToEnd());
        }
      } catch { }
      if (then != null) window.Dispatcher.BeginInvoke(new Action(delegate { then(answer); }));
    });
  }

  void TogglePanel() {
    foreach (Process process in Process.GetProcesses()) {
      try {
        if (process.MainWindowTitle == "Reduction" && (process.ProcessName == "msedge" || process.ProcessName == "chrome")) { process.CloseMainWindow(); return; }
      } catch { }
    }
    if (browser == "") { MessageBox.Show("没有找到可以开面板的程序。可以在浏览器里打开 " + url); return; }
    Rect area = SystemParameters.WorkArea;
    // Beside the pet, on whichever side there is room.
    int x = (int)(window.Left - 410); if (x < area.Left) x = (int)(window.Left + 330);
    int y = (int)Math.Max(area.Top, Math.Min(window.Top - 560, area.Bottom - 800));
    Process.Start(browser, string.Format("--app={0} \"--proxy-bypass-list=localhost;127.0.0.1\" \"--user-data-dir={1}\" --window-size=420,780 --window-position={2},{3} --no-first-run", url, profile, x, y));
  }

  [STAThread] static void Main(string[] args) {
    string snapshot = null, pose = "idle"; double at = 3;
    for (int i = 0; i + 1 < args.Length; i += 2) {
      if (args[i] == "--snapshot") snapshot = args[i + 1]; else if (args[i] == "--pose") pose = args[i + 1];
      else if (args[i] == "--at") at = double.Parse(args[i + 1], System.Globalization.CultureInfo.InvariantCulture);
    }
    if (snapshot != null) { new Pet().Snapshot(snapshot, pose, at); return; }
    bool first; Mutex one = new Mutex(true, "ReductionPet", out first);
    if (!first) return;
    new Pet().Run(args);
    GC.KeepAlive(one);
  }
}
