package day2;
import java.util.Scanner;//注意scanner相当于java提前写好的类，要先把对应的类引入然后才能用
public class Scanner类学习 {

    public static  void main(String[] args){
        Scanner scanner =  new Scanner(System.in);//简单创建一个类
        for(int i=0;i<9;i++){
        int j=scanner.nextInt();
        System.out.println(j);
        }
    }
}
