# DotNative.HoloDatePicker

Three numeric wheels (day, month, year), inspired by HoloDatePicker. Supports bounded dates, looping, labels, and colors. No external UI runtime is required.

## Usage

```csharp
using DotNative.HoloDatePicker;

builder.Services.AddHoloDatePicker(new DatePickerOptions
{
    MinimumDate = new(1930, 1, 1),
    MaximumDate = DateOnly.FromDateTime(DateTime.Today),
    Title = "Datum rođenja",
    ConfirmText = "Sačuvaj",
    CancelText = "Otkaži",
});

// Inject IHoloDatePicker into a component or service.
DateOnly? selected = await picker.ShowAsync(cancellationToken: token);
// Override registration defaults for one dialog with ShowAsync(options, token).
```

Dismissal returns null. Invalid bounds throw before opening a dialog. Dates are
calendar dates, without a time zone. Calls through one service are serialized;
only one dialog per native plugin can be open. Cancellation dismisses the dialog.

On net10.0 and later, `services.HoloDatePicker` is an extension property.
On net9.0, use `services.HoloDatePicker()`. The APIs are mutually exclusive.
The .NET 9 SDK builds only net9.0; the .NET 10 SDK also builds net10.0 with C# 14.

## Platforms

Android and iOS are implemented. Desktop implementations are not included.
The custom renderers use a 160-point picker, 36-point rows, 7/18 padding,
0.95 squeeze, 1.5 diameter ratio, 0.003 perspective, and 0.447 opacity outside
the flat center band. Each column has two 2-point dividers with spacing equal to
2% of the screen width. The dialog has 14-point content padding, 24-point title
and action padding, a 28-point radius, and confirm-before-cancel actions.

Pass FontData, TitleFontData, and ActionFontData (TTF/OTF, up to 8 MiB each),
with FontSize, TitleFontSize and ActionFontSize, to reproduce an application's
typography. Fonts are hashed, uploaded in bounded chunks, and cached by the
native plugin. ItemColor controls row text and dividers; TextColor controls the
title and actions. No native wheel selection overlay is drawn.

Bounds, leap years, looping, dependent month/day ranges, drag, fling, snapping,
tap-to-select and accessibility increment/decrement are implemented. Screenshot
parity across device scales and motion timing have not been independently measured.

## Source development

Set `DotNativeSourceRoot` in ignored `dotnative.local.props` to a DotNative checkout,
or place this repository next to `dotNative`. Otherwise the package reference is used.

```sh
dotnet build DotNative.HoloDatePicker.csproj
dotnative plugin run ios --project .
dotnative plugin run android --project .
```

The `example` app opens the real picker. Native sources use the named
`dotnative.holodatepicker` channel and are included through buildTransitive
when packaged. No generated channel-descriptor files are required.

License: MIT.
