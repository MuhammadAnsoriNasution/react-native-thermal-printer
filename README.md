# @muhammadansorinasution/react-native-thermal-printer

![npm](https://img.shields.io/npm/dw/react-native-thermal-receipt-printer-image-qr?logo=github)
![npm](https://img.shields.io/npm/v/react-native-thermal-receipt-printer-image-qr?color=green&logo=npm&logoColor=green)

- I forked this for my quickly project, this is not the official project.
- Fork of [`react-native-thermal-receipt-printer-image-qr`](https://www.npmjs.com/package/react-native-thermal-receipt-printer)

## Support

| Printer    | Android            | IOS                |
| ---------- | ------------------ | ------------------ |
| USBPrinter | :heavy_check_mark: |                    |
| BLEPrinter | :heavy_check_mark: | :heavy_check_mark: |
| NetPrinter | :heavy_check_mark: | :heavy_check_mark: |

<br />
<div style="display: flex; flex-direction: row; align-self: center; align-items: center">
<img src="image/invoice.jpg" alt="bill" width="270" height="580"/>
<img src="image/_screenshot.jpg" alt="screenshot" width="270" height="580"/>
</div>

## Installation

```
npm i @muhammadansorinasution/react-native-thermal-printer
npm i react-native-ping
```

or

```
yarn add @muhammadansorinasution/react-native-thermal-printer
yarn add react-native-ping
```

next step

```
# RN >= 0.60
cd ios && pod install

# RN < 0.60
react-native link @muhammadansorinasution/react-native-thermal-printer
```

## API Reference

```tsx
    init: () => Promise;
    getDeviceList: () => Promise;
    /**
     * `timeout`
     * @default 4000ms
     */
    connectPrinter: (host: string, port: number, timeout?: number | undefined) => Promise;
    closeConn: () => Promise;
    /**
     * Print text
     */
    printText: (text: string, opts?: {}) => void;
    /**
     * Print text & end the bill & cut
     */
    printBill: (text: string, opts?: PrinterOptions) => void;
    /**
     * print with image url
     */
    printImage: (imgUrl: string, opts?: PrinterImageOptions) => void;
    /**
     * Base 64 string
     */
    printImageBase64: (Base64: string, opts?: PrinterImageOptions) => void;
    /**
     * Only android print with encoder
     */
    printRaw: (text: string) => void;
    /**
     * print column
     * 80mm => 46 character
     * 58mm => 30 character
     */
    printColumnsText: (texts: string[], columnWidth: number[], columnAlignment: ColumnAlignment[], columnStyle?: string[], opts?: PrinterOptions) => void;
```

## Styling

```js
import {
  COMMANDS,
  ColumnAlignment,
} from "react-native-thermal-printer";
```

[See more here](https://github.com/MuhammadAnsoriNasution/react-native-thermal-printer/blob/main/dist/utils/printer-commands.js)

## Example

**`Print Columns Text`**

```tsx
const BOLD_ON = COMMANDS.TEXT_FORMAT.TXT_BOLD_ON;
const BOLD_OFF = COMMANDS.TEXT_FORMAT.TXT_BOLD_OFF;
let orderList = [
  ["1. Skirt Palas Labuh Muslimah Fashion", "x2", "500$"],
  ["2. BLOUSE ROPOL VIRAL MUSLIMAH FASHION", "x4222", "500$"],
  [
    "3. Women Crew Neck Button Down Ruffle Collar Loose Blouse",
    "x1",
    "30000000000000$",
  ],
  ["4. Retro Buttons Up Full Sleeve Loose", "x10", "200$"],
  ["5. Retro Buttons Up", "x10", "200$"],
];
let columnAlignment = [
  ColumnAlignment.LEFT,
  ColumnAlignment.CENTER,
  ColumnAlignment.RIGHT,
];
let columnWidth = [46 - (7 + 12), 7, 12];
const header = ["Product list", "Qty", "Price"];
Printer.printColumnsText(header, columnWidth, columnAlignment, [
  `${BOLD_ON}`,
  "",
  "",
]);
for (let i in orderList) {
  Printer.printColumnsText(orderList[i], columnWidth, columnAlignment, [
    `${BOLD_OFF}`,
    "",
    "",
  ]);
}
Printer.printBill(`${CENTER}Thank you\n`);
```

**`Print image`**

```tsx
Printer.printImage(
  "https://media-cdn.tripadvisor.com/media/photo-m/1280/1b/3a/bd/b5/the-food-bill.jpg",
  {
    imageWidth: 575,
    // imageHeight: 1000,
    // paddingX: 100
  }
);
```

[See more here](https://github.com/MuhammadAnsoriNasution/react-native-thermal-printer/blob/main/example/src/HomeScreen.tsx)

## Troubleshoot

- When installing `react-native` version >= 0.60, XCode shows this error:

```
duplicate symbols for architecture x86_64
```

That's because the .a library uses [CocoaAsyncSocket](https://github.com/robbiehanson/CocoaAsyncSocket) library and Flipper uses it too.

_Podfile_

```diff
...
  use_native_modules!

  # Enables Flipper.
  #
  # Note that if you have use_frameworks! enabled, Flipper will not work and
  # you should disable these next few lines.
  # add_flipper_pods!
  # post_install do |installer|
  #   flipper_post_install(installer)
  # end
...
```

and comment out code related to Flipper in `ios/AppDelegate.m`

## Usage

```javascript
import {
  USBPrinter,
  NetPrinter,
  BLEPrinter,
} from "react-native-thermal-receipt-printer";

USBPrinter.printText("<C>sample text</C>");
USBPrinter.printBill("<C>sample bill</C>");
```

## Example

### USBPrinter (only support android)

```typescript
interface IUSBPrinter {
  device_name: string;
  vendor_id: number;
  product_id: number;
}
```

```javascript
  const [printers, setPrinters] = useState([]);
  const [currentPrinter, setCurrentPrinter] = useState();

  useEffect = () => {
    if(Platform.OS == 'android'){
      USBPrinter.init().then(()=> {
        //list printers
        USBPrinter.getDeviceList().then(setPrinters);
      })
    }
  }

  const _connectPrinter = (printer) => USBPrinter.connectPrinter(printer.vendorID, printer.productId).then(() => setCurrentPrinter(printer))

  const printTextTest = () => {
    currentPrinter && USBPrinter.printText("<C>sample text</C>\n");
  }

  const printBillTest = () => {
    currentPrinter && USBPrinter.printBill("<C>sample bill</C>");
  }

  ...

  return (
    <View style={styles.container}>
      {
        printers.map(printer => (
          <TouchableOpacity key={printer.device_id} onPress={() => _connectPrinter(printer)}>
            {`device_name: ${printer.device_name}, device_id: ${printer.device_id}, vendor_id: ${printer.vendor_id}, product_id: ${printer.product_id}`}
          </TouchableOpacity>
          ))
      }
      <TouchableOpacity onPress={printTextTest}>
        <Text>Print Text</Text>
      </TouchableOpacity>
      <TouchableOpacity onPress={printBillTest}>
        <Text>Print Bill Text</Text>
      </TouchableOpacity>
    </View>
  )

  ...

```

### BLEPrinter

```typescript
interface IBLEPrinter {
  device_name: string;
  inner_mac_address: string;
}
```

```javascript
  const [printers, setPrinters] = useState([]);
  const [currentPrinter, setCurrentPrinter] = useState();

  useEffect(() => {
    BLEPrinter.init().then(()=> {
      BLEPrinter.getDeviceList().then(setPrinters);
    });
  }, []);

  const _connectPrinter = (printer) => {
    //connect printer
    BLEPrinter.connectPrinter(printer.inner_mac_address).then(
      setCurrentPrinter,
      error => console.warn(error))
  }

  const printTextTest = () => {
    currentPrinter && BLEPrinter.printText("<C>sample text</C>\n");
  }

  const printBillTest = () => {
    currentPrinter && BLEPrinter.printBill("<C>sample bill</C>");
  }

  ...

  return (
    <View style={styles.container}>
      {
        this.state.printers.map(printer => (
          <TouchableOpacity key={printer.inner_mac_address} onPress={() => _connectPrinter(printer)}>
            {`device_name: ${printer.device_name}, inner_mac_address: ${printer.inner_mac_address}`}
          </TouchableOpacity>
          ))
      }
      <TouchableOpacity onPress={printTextTest}>
        <Text>Print Text</Text>
      </TouchableOpacity>
      <TouchableOpacity onPress={printBillTest}>
        <Text>Print Bill Text</Text>
      </TouchableOpacity>
    </View>
  )

  ...

```

### NetPrinter

```typescript
interface INetPrinter {
  device_name: string;
  host: string;
  port: number;
}
```

_Note:_ get list device for net printers is support scanning in local ip but not recommended

```javascript

  componentDidMount = () => {
    NetPrinter.init().then(() => {
      this.setState(Object.assign({}, this.state, {printers: [{host: '192.168.10.241', port: 9100}]}))
      })
  }

  _connectPrinter => (host, port) => {
    //connect printer
    NetPrinter.connectPrinter(host, port).then(
      (printer) => this.setState(Object.assign({}, this.state, {currentPrinter: printer})),
      error => console.warn(error))
}

  printTextTest = () => {
    if (this.state.currentPrinter) {
      NetPrinter.printText("<C>sample text</C>\n");
    }
  }

  printBillTest = () => {
    if(this.state.currentPrinter) {
      NetPrinter.printBill("<C>sample bill</C>");
    }
  }

  ...

  render() {
    return (
      <View style={styles.container}>
        {
          this.state.printers.map(printer => (
            <TouchableOpacity key={printer.device_id} onPress={(printer) => this._connectPrinter(printer.host, printer.port)}>
              {`device_name: ${printer.device_name}, host: ${printer.host}, port: ${printer.port}`}
            </TouchableOpacity>
            ))
        }
        <TouchableOpacity onPress={() => this.printTextTest()}>
          <Text> Print Text </Text>
        </TouchableOpacity>
        <TouchableOpacity onPress={() => this.printBillTest()}>
          <Text> Print Bill Text </Text>
        </TouchableOpacity>
      </View>
    )
  }

  ...

```