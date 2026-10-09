using DotNative;
using DotNative.HoloDatePicker;

namespace HoloDatePickerDemo;

public static class Program
{
    public static void Main()
    {
        var builder = DotNativeApplication.CreateBuilder();
        builder.Services.AddHoloDatePicker();
        builder.Build().Run<App>();
    }
}
