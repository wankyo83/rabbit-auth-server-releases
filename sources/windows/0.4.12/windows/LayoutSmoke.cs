using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Reflection;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Forms;

static class LayoutSmoke {
    static T Field<T>(object target,string name) where T:class {
        return (T)target.GetType().GetField(name,BindingFlags.Instance|BindingFlags.NonPublic).GetValue(target);
    }
    static void Capture(Control control,string path) {
        using(Bitmap image=new Bitmap(control.Width,control.Height)){
            control.DrawToBitmap(image,new Rectangle(Point.Empty,control.Size));
            image.Save(path,ImageFormat.Png);
        }
    }
    static void Buttons(Control root,List<Button> result) {
        foreach(Control child in root.Controls){
            Button button=child as Button;if(button!=null&&button.Text.Contains("주소 복사"))result.Add(button);
            Buttons(child,result);
        }
    }
    static bool VisibleInside(Control child,Control viewport) {
        Rectangle area=viewport.RectangleToClient(child.RectangleToScreen(child.ClientRectangle));
        return child.Visible&&area.Width>0&&area.Height>0&&viewport.ClientRectangle.IntersectsWith(area);
    }
    [STAThread] public static int Main(string[] args) {
        if(args.Length!=1)throw new ArgumentException("Output directory required");
        Directory.CreateDirectory(args[0]);
        Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
        ServerForm form=new ServerForm();
        Field<System.Windows.Forms.Timer>(form,"timer").Stop();
        MethodInfo render=form.GetType().GetMethod("RenderAddresses",BindingFlags.Instance|BindingFlags.NonPublic);
        render.Invoke(form,new object[]{new List<string>{
            "http://192.168.0.57:9870",
            "http://100.112.16.6:9870",
            "http://172.16.0.2:9870",
            "http://192.168.182.1:9870",
            "http://192.168.8.1:9870"
        }});
        Panel page=Field<Panel>(form,"scrollHost"),addresses=Field<Panel>(form,"addressScroll");
        TableLayoutPanel rows=Field<TableLayoutPanel>(form,"addressRows");
        ModernButton update=Field<ModernButton>(form,"update");
        Size[] sizes={new Size(1326,544),new Size(1024,600),new Size(800,600),new Size(1280,900)};
        var results=new List<Dictionary<string,object>>();
        form.Show();
        foreach(Size size in sizes){
            form.ClientSize=size;page.AutoScrollPosition=Point.Empty;addresses.AutoScrollPosition=Point.Empty;
            Application.DoEvents();
            string key=size.Width+"x"+size.Height;
            Capture(form,Path.Combine(args[0],key+"-top.png"));
            var buttons=new List<Button>();Buttons(rows,buttons);
            page.AutoScrollPosition=new Point(0,Math.Max(0,page.DisplayRectangle.Height-page.ClientSize.Height));
            Application.DoEvents();
            if(buttons.Count>0)addresses.ScrollControlIntoView(buttons[buttons.Count-1]);
            Application.DoEvents();
            Capture(form,Path.Combine(args[0],key+"-addresses.png"));
            bool lastCopyVisible=buttons.Count>0&&VisibleInside(buttons[buttons.Count-1],addresses);
            bool footerVisible=VisibleInside(update,page);
            results.Add(new Dictionary<string,object>{{"size",key},{"pageScroll",page.VerticalScroll.Maximum},{"pageValue",page.VerticalScroll.Value},{"pageLargeChange",page.VerticalScroll.LargeChange},{"pageDisplayHeight",page.DisplayRectangle.Height},{"pageClientHeight",page.ClientSize.Height},{"addressScroll",addresses.VerticalScroll.Maximum},{"copyButtons",buttons.Count},{"lastCopyVisible",lastCopyVisible},{"footerVisible",footerVisible},{"rowHeight",rows.Height},{"addressViewportHeight",addresses.ClientSize.Height}});
        }
        form.Close();form.Dispose();
        var report=new Dictionary<string,object>{{"ok",true},{"addresses",5},{"results",results}};
        File.WriteAllText(Path.Combine(args[0],"layout-smoke.json"),new JavaScriptSerializer().Serialize(report),new UTF8Encoding(false));
        foreach(var item in results){
            if(!(bool)item["lastCopyVisible"]||!(bool)item["footerVisible"]||(int)item["copyButtons"]!=5||(int)item["addressViewportHeight"]<120)return 2;
        }
        return 0;
    }
}
