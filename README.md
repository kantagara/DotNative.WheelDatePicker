# DotNative.WheelDatePicker

Date selection with automatic native renderers and a shared wheel fallback. No external UI runtime is required.

## Usage

```csharp
using DotNative.WheelDatePicker;

builder.Services.AddWheelDatePicker(new DatePickerOptions
{
    MinimumDate = new(1930, 1, 1),
    MaximumDate = DateOnly.FromDateTime(DateTime.Today),
    Title = "Datum rođenja",
    ConfirmText = "Sačuvaj",
    CancelText = "Otkaži",
});

// Inject IWheelDatePicker into a component or service.
DateOnly? selected = await picker.ShowAsync(cancellationToken: token);
// Override registration defaults for one dialog with ShowAsync(options, token).
```

Dismissal returns null. Invalid bounds throw before opening a dialog. Dates are
calendar dates, without a time zone. Calls through one service are serialized;
only one dialog per native plugin can be open. Cancellation dismisses the dialog.

On net10.0 and later, `services.WheelDatePicker` is an extension property.
On net9.0, use `services.WheelDatePicker()`. The APIs are mutually exclusive.
The .NET 9 SDK builds only net9.0; the .NET 10 SDK also builds net10.0 with C# 14.

## Platforms

The plugin selects native wheel controls on iOS and Android. macOS, Windows
and Linux use DotNative's shared `WheelPicker` and `IOverlayPresenter`. The
shared renderer can also be selected explicitly on every platform.

The wheel uses 160-point height, 36-point rows, 7/18 padding, 0.95 squeeze,
1.5 diameter ratio, 0.003 perspective, and 0.447 opacity outside the center band.
Rows use centered layout inside the selection dividers. Month and year changes
clamp the day to a valid calendar date. The dialog fades in and out.

Pass FontData, TitleFontData and ActionFontData with their matching font families
and sizes to reproduce application typography. DotNative registers the fonts.
ItemColor controls wheel text and dividers; TextColor controls title and actions.
SafeArea controls dialog positioning when the host draws beneath system bars.

Screenshot parity and motion timing against the reference remain unmeasured.
Windows and Linux renderer support is implemented but needs runtime verification
on those operating systems.

## Source development

This revision requires the DotNative source checkout containing `WheelPicker`
and `IOverlayPresenter`; these APIs are not yet in the published preview package.
Set `DotNativeSourceRoot` in ignored `dotnative.local.props`, or place this
repository next to `dotNative`. The example imports the same local override.

```sh
dotnet build DotNative.WheelDatePicker.csproj
dotnative plugin run mac --project .
dotnative plugin run ios --project .
dotnative plugin run android --project .
```

The small example starts on 31 January 2024 with 2023–2025 bounds. Select
February to check leap-day clamping, then confirm or cancel and read the result.

License: MIT.

## Platform renderer selection

The existing `IWheelDatePicker` service automatically uses UIDatePicker (wheels)
on iOS and the native spinner DatePickerDialog on Android. macOS, Windows
and Linux use the shared wheel renderer. Platform detection
uses the presentation target, including when previewing another device.

Set `DatePickerOptions.Renderer = DatePickerRenderer.SharedWheel` to explicitly
use the shared wheel. Its scrolling settles at an exact item offset. Font, color,
perspective, and looping options apply to that shared renderer; native controls
use the operating system's presentation. Both plugins can be registered together:
this plugin retains `dotnative.wheeldatepicker`, while NativeDatePicker retains
`dotnative.nativedatepicker`.
