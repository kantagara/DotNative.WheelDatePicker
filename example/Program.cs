using DotNative;
using DotNative.WheelDatePicker;

namespace WheelDatePickerDemo;

public static class Program
{
    public static void Main()
    {
        var builder = DotNativeApplication.CreateBuilder();
        builder.Services.AddWheelDatePicker();
        builder.Build().Run<App>();
    }
}
